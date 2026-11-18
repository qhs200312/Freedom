package com.v2ray.ang.root

/** Root-side process identity checks prevent stale pid files from killing reused PIDs. */
internal object RootProcessScript {
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    val stopFunction = """
        stop_recorded() {
          file="${'$'}1"; expected="${'$'}2"; signal="${'$'}3"
          p=${'$'}(cat "${'$'}file" 2>/dev/null)
          rm -f "${'$'}file"
          case "${'$'}p" in ''|*[!0-9]*|0|1) return 0;; esac
          tr '\000' ' ' < "/proc/${'$'}p/cmdline" 2>/dev/null | grep -Fq -- "${'$'}expected" || return 0
          stamp=${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}p/stat" 2>/dev/null)
          [ -n "${'$'}stamp" ] || return 0
          kill -"${'$'}signal" "${'$'}p" 2>/dev/null || true
          i=0
          while [ "${'$'}i" -lt 10 ] && kill -0 "${'$'}p" 2>/dev/null; do
            [ "${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}p/stat" 2>/dev/null)" = "${'$'}stamp" ] || return 0
            sleep 0.05; i=${'$'}((i+1))
          done
          [ "${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}p/stat" 2>/dev/null)" != "${'$'}stamp" ] || kill -KILL "${'$'}p" 2>/dev/null || true
        }
    """.trimIndent() + "\n"

    fun watchdog(corePid: Int, token: String, directory: String, helperPidFile: String): String {
        require(corePid > 1)
        require(token.matches(Regex("[A-Za-z0-9-]+")))
        val sessionFile = quote("$directory/session")
        return """
            core=$corePid
            owner=${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}core/stat" 2>/dev/null)
            [ -n "${'$'}owner" ] || exit 1
            helper=${'$'}(cat ${quote(helperPidFile)} 2>/dev/null)
            case "${'$'}helper" in ''|*[!0-9]*|0|1) exit 1;; esac
            helper_start=${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}helper/stat" 2>/dev/null)
            while [ "${'$'}(cat $sessionFile 2>/dev/null)" = ${quote(token)} ]; do
              live_owner=${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}core/stat" 2>/dev/null)
              live_helper=${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}helper/stat" 2>/dev/null)
              if [ "${'$'}live_owner" != "${'$'}owner" ] || [ -z "${'$'}helper_start" ] || [ "${'$'}live_helper" != "${'$'}helper_start" ]; then
                [ "${'$'}(cat $sessionFile 2>/dev/null)" = ${quote(token)} ] || exit 0
                rm -f ${quote("$directory/watchdog.pid")}
                sh ${quote("$directory/teardown_rules.sh")} >/dev/null 2>&1
                if [ "${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}core/stat" 2>/dev/null)" = "${'$'}owner" ]; then
                  kill "${'$'}core" 2>/dev/null || true
                fi
                exit 0
              fi
              sleep 2
            done
        """.trimIndent() + "\n"
    }
}
