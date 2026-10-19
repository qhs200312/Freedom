// Copyright (C) 2026 NodePassProject <https://github.com/NodePassProject>
// SPDX-License-Identifier: GPL-3.0-only

//! UTC service information and raw error output.

use std::fmt;
use std::io::{self, Write};
use std::sync::Mutex;

static OUTPUT: Mutex<()> = Mutex::new(());

#[derive(Clone, Copy, Debug, Default)]
pub struct Logger;

impl Logger {
    pub fn info(&self, args: fmt::Arguments<'_>) {
        self.write(args, false);
    }
    pub fn error(&self, args: fmt::Arguments<'_>) {
        self.write(args, true);
    }
    pub fn flush(&self) {
        let _ = io::stdout().flush();
        let _ = io::stderr().flush();
    }
    fn write(&self, args: fmt::Arguments<'_>, error: bool) {
        let line = format!(
            "{} {}\n",
            chrono::Utc::now().format("%Y-%m-%dT%H:%M:%S%.3fZ"),
            args.to_string().replace('\r', "\\r").replace('\n', "\\n")
        );
        let _guard = OUTPUT.lock().unwrap_or_else(|lock| lock.into_inner());
        if error {
            let _ = io::stderr().write_all(line.as_bytes());
        } else {
            let _ = io::stdout().write_all(line.as_bytes());
        }
    }
}
