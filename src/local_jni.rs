//! JNI entry points of the local-only mode for the Android app (`local`).
//! Kotlin side: `blue.anika.wolle.localserver.LocalServerNative`.

use std::path::PathBuf;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;

/// `start(dataDir: String, preferredPort: Int): String` answers `"<port>\n<token>"`, or throws
/// `IllegalStateException` with the reason.
#[unsafe(no_mangle)]
pub extern "system" fn Java_blue_anika_wolle_localserver_LocalServerNative_start<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    data_dir: JString<'local>,
    preferred_port: jni::sys::jint,
) -> jstring {
    let data_dir: String = match env.get_string(&data_dir) {
        Ok(value) => value.into(),
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                "invalid data directory",
            );
            return std::ptr::null_mut();
        }
    };
    match crate::local::start(
        &PathBuf::from(data_dir),
        u16::try_from(preferred_port).unwrap_or(0),
    ) {
        Ok(started) => match env.new_string(format!("{}\n{}", started.port, started.token)) {
            Ok(value) => value.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error.to_string());
            std::ptr::null_mut()
        }
    }
}

/// `stop()` stops the running server, if any.
#[unsafe(no_mangle)]
pub extern "system" fn Java_blue_anika_wolle_localserver_LocalServerNative_stop<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    crate::local::stop();
}
