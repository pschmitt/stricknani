//! JNI entry points of the local-only mode for the Android app (`local`).
//! Kotlin side: `blue.anika.wolle.localserver.LocalServerNative`.

use std::path::PathBuf;

use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JString};

/// Why a call failed; thrown to Kotlin as a `RuntimeException` with this message.
#[derive(Debug, thiserror::Error)]
enum CallError {
    #[error(transparent)]
    Jni(#[from] jni::errors::Error),
    #[error(transparent)]
    Local(#[from] crate::local::LocalError),
}

/// `start(dataDir: String, preferredPort: Int): String` answers `"<port>\n<token>"`, or throws
/// a `RuntimeException` with the reason.
#[unsafe(no_mangle)]
pub extern "system" fn Java_blue_anika_wolle_localserver_LocalServerNative_start<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    data_dir: JString<'caller>,
    preferred_port: jni::sys::jint,
) -> JString<'caller> {
    unowned_env
        .with_env(|env| -> Result<JString<'caller>, CallError> {
            let data_dir = data_dir.try_to_string(env)?;
            let started = crate::local::start(
                &PathBuf::from(data_dir),
                u16::try_from(preferred_port).unwrap_or(0),
            )?;
            Ok(env.new_string(format!("{}\n{}", started.port, started.token))?)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// `stop()` stops the running server, if any.
#[unsafe(no_mangle)]
pub extern "system" fn Java_blue_anika_wolle_localserver_LocalServerNative_stop<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
) {
    unowned_env
        .with_env(|_env| -> Result<(), CallError> {
            crate::local::stop();
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}
