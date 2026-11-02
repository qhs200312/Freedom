use crate::{parse, Budget, Failure, LimitedWriter, ParseError, Parsed, PARSE_TIMEOUT};
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jbyteArray, jlong, jobjectArray, jstring};
use jni::JNIEnv;
use serde::Serialize;
use std::collections::HashMap;
use std::fs::File;
use std::io::{BufReader, Read};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

static NEXT: AtomicI64 = AtomicI64::new(1);
static SESSIONS: OnceLock<Mutex<HashMap<i64, Arc<AtomicBool>>>> = OnceLock::new();

fn sessions() -> &'static Mutex<HashMap<i64, Arc<AtomicBool>>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn session(id: jlong) -> Result<Arc<AtomicBool>, ParseError> {
    sessions()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(&id)
        .cloned()
        .ok_or_else(|| ParseError::new(Failure::Cancelled, 0, ""))
}

fn binary(
    env: &mut JNIEnv,
    work: impl FnOnce(&mut JNIEnv) -> Result<Vec<u8>, ParseError>,
) -> jbyteArray {
    let bytes = catch_unwind(AssertUnwindSafe(|| work(env)))
        .unwrap_or_else(|_| Err(ParseError::new(Failure::Read, 0, "native panic")))
        .unwrap_or_else(crate::packet::error);
    env.byte_array_from_slice(&bytes)
        .map(|array| array.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_v2ray_ang_routing_RustRuleParser_nativeParsePacket(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    id: jlong,
) -> jbyteArray {
    binary(&mut env, |env| {
        let path: String = env
            .get_string(&path)
            .map_err(|_| ParseError::new(Failure::Read, 0, "path"))?
            .into();
        let flag = session(id)?;
        let budget = Budget::new(&flag, PARSE_TIMEOUT);
        budget.check(0)?;
        let file = File::open(path).map_err(|_| ParseError::new(Failure::Read, 0, "file"))?;
        crate::packet::rules(parse(BufReader::with_capacity(8192, file), &budget)?)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_v2ray_ang_routing_RustRuleParser_nativeSubscription(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    id: jlong,
    encoded: jboolean,
) -> jbyteArray {
    binary(&mut env, |env| {
        let path: String = env
            .get_string(&path)
            .map_err(|_| ParseError::new(Failure::Read, 0, "path"))?
            .into();
        let flag = session(id)?;
        let budget = Budget::new(&flag, PARSE_TIMEOUT);
        budget.check(0)?;
        let file = File::open(path).map_err(|_| ParseError::new(Failure::Read, 0, "file"))?;
        let mut bytes = Vec::new();
        file.take((crate::MAX_BYTES + 1) as u64)
            .read_to_end(&mut bytes)
            .map_err(|_| ParseError::new(Failure::Read, 0, "subscription file"))?;
        crate::subscription::prepare(&bytes, encoded != 0, &budget)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_v2ray_ang_routing_RustRuleParser_nativeParseText(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    id: jlong,
) -> jobjectArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let path: String = env
            .get_string(&path)
            .map_err(|_| ParseError::new(Failure::Read, 0, "path"))?
            .into();
        let flag = session(id)?;
        let budget = Budget::new(&flag, PARSE_TIMEOUT);
        budget.check(0)?;
        let file = File::open(path).map_err(|_| ParseError::new(Failure::Read, 0, "file"))?;
        parse(BufReader::with_capacity(8192, file), &budget)
    }))
    .unwrap_or_else(|_| Err(ParseError::new(Failure::Read, 0, "native panic")));
    let packet = catch_unwind(AssertUnwindSafe(|| crate::text_packet::encode(result)))
        .unwrap_or_else(|_| {
            crate::text_packet::encode(Err(ParseError::new(Failure::Read, 0, "native panic")))
        });
    // Only three array objects cross JNI, regardless of the number of rule strings.
    let output = (|| -> jni::errors::Result<jobjectArray> {
        let numbers = env.new_int_array(packet.numbers.len() as i32)?;
        env.set_int_array_region(&numbers, 0, &packet.numbers)?;
        let text = env.new_char_array(packet.text.len() as i32)?;
        env.set_char_array_region(&text, 0, &packet.text)?;
        let output = env.new_object_array(2, "java/lang/Object", JObject::null())?;
        env.set_object_array_element(&output, 0, &numbers)?;
        env.set_object_array_element(&output, 1, &text)?;
        Ok(output.into_raw())
    })();
    output.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_v2ray_ang_routing_RustRuleParser_nativeCreate(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    let mut sessions = sessions().lock().unwrap_or_else(|e| e.into_inner());
    if sessions.len() >= 2 {
        return 0;
    }
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    sessions.insert(id, Arc::new(AtomicBool::new(false)));
    id
}

#[no_mangle]
pub extern "system" fn Java_com_v2ray_ang_routing_RustRuleParser_nativeCancel(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) {
    if let Some(flag) = sessions()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(&id)
    {
        flag.store(true, Ordering::Relaxed);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_v2ray_ang_routing_RustRuleParser_nativeRelease(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) {
    sessions()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .remove(&id);
}

#[derive(Serialize)]
#[serde(untagged)]
enum Response {
    Success {
        ok: bool,
        #[serde(flatten)]
        parsed: Parsed,
    },
    Error {
        ok: bool,
        #[serde(flatten)]
        error: ParseError,
    },
}

#[no_mangle]
pub extern "system" fn Java_com_v2ray_ang_routing_RustRuleParser_nativeParseFile(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    id: jlong,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let path: String = env
            .get_string(&path)
            .map_err(|_| ParseError::new(Failure::Read, 0, "path"))?
            .into();
        let flag = sessions()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&id)
            .cloned()
            .ok_or_else(|| ParseError::new(Failure::Cancelled, 0, ""))?;
        let budget = Budget::new(&flag, PARSE_TIMEOUT);
        budget.check(0)?;
        let file = File::open(path).map_err(|_| ParseError::new(Failure::Read, 0, "file"))?;
        if file
            .metadata()
            .map_err(|_| ParseError::new(Failure::Read, 0, "metadata"))?
            .len()
            > crate::MAX_BYTES as u64
        {
            return Err(ParseError::new(Failure::TooLarge, 0, "file"));
        }
        parse(BufReader::with_capacity(8192, file), &budget)
    }))
    .unwrap_or_else(|_| Err(ParseError::new(Failure::Read, 0, "native panic")));
    let response = match result {
        Ok(parsed) => Response::Success { ok: true, parsed },
        Err(error) => Response::Error { ok: false, error },
    };
    let mut writer = LimitedWriter {
        bytes: Vec::with_capacity(4096),
    };
    let json = if serde_json::to_writer(&mut writer, &response).is_ok() {
        String::from_utf8(writer.bytes).unwrap_or_default()
    } else {
        r#"{"ok":false,"failure":"TOO_LARGE","line":0,"detail":"result budget"}"#.to_owned()
    };
    env.new_string(json)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
