//! Stable errors. The shared catalog is embedded at compile time.
use serde_json::{json, Value};
use std::{error::Error, fmt, sync::OnceLock};

static CATALOG: OnceLock<Value> = OnceLock::new();
fn catalog() -> &'static Value {
    CATALOG.get_or_init(|| {
        serde_json::from_str(include_str!("../../failure_codes.json"))
            .expect("valid failure catalog")
    })
}
#[derive(Debug)]
pub(crate) struct Failure {
    pub code: &'static str,
    pub details: Value,
}
impl Failure {
    pub fn new(code: &'static str) -> Self {
        assert!(catalog().get(code).is_some(), "registered failure code");
        Self {
            code,
            details: json!({}),
        }
    }
    pub fn input(field: &str) -> Self {
        Self {
            code: "ATLAS_INPUT_INVALID",
            details: json!({"field":field}),
        }
    }
    pub fn payload(&self) -> Value {
        let spec = &catalog()[self.code];
        json!({"code":self.code,"message":spec["summary"],"category":spec["category"],
            "status":spec["status"],"retryable":spec["retryable"],"action":spec["action"],"details":self.details})
    }
    pub fn classify(error: &(dyn Error + 'static)) -> Self {
        if let Some(typed) = error.downcast_ref::<Self>() {
            return Self {
                code: typed.code,
                details: typed.details.clone(),
            };
        }
        if let Some(sql) = error.downcast_ref::<rusqlite::Error>() {
            use rusqlite::{Error as SqlError, ErrorCode};
            let code = match sql {
                SqlError::QueryReturnedNoRows => "ATLAS_NOT_FOUND",
                SqlError::SqliteFailure(failure, _) => match failure.code {
                    ErrorCode::DatabaseBusy | ErrorCode::DatabaseLocked => "ATLAS_STORAGE_BUSY",
                    ErrorCode::DiskFull => "ATLAS_STORAGE_FULL",
                    ErrorCode::DatabaseCorrupt | ErrorCode::NotADatabase => "ATLAS_STORAGE_CORRUPT",
                    ErrorCode::ConstraintViolation => "ATLAS_CONFLICT",
                    _ => "ATLAS_STORAGE_FAILURE",
                },
                _ => "ATLAS_STORAGE_FAILURE",
            };
            return Self::new(code);
        }
        if error.is::<serde_json::Error>() {
            return Self::new("ATLAS_JSON_INVALID");
        }
        if error.is::<std::net::AddrParseError>() {
            return Self::input("host");
        }
        if let Some(io) = error.downcast_ref::<std::io::Error>() {
            return Self::new(if io.raw_os_error() == Some(28) {
                "ATLAS_STORAGE_FULL"
            } else {
                "ATLAS_IO_FAILED"
            });
        }
        Self::new("ATLAS_INTERNAL")
    }
}
impl fmt::Display for Failure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            self.payload()["message"]
                .as_str()
                .unwrap_or("Atlas failure")
        )
    }
}
impl Error for Failure {}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn catalog_is_complete() {
        for (_, spec) in catalog().as_object().unwrap() {
            assert!(spec["status"].is_number());
            assert!(spec["retryable"].is_boolean());
            assert!(!spec["action"].as_str().unwrap().is_empty());
        }
    }
    #[test]
    fn sqlite_classification_does_not_parse_text() {
        let sql = rusqlite::Error::SqliteFailure(
            rusqlite::ffi::Error::new(rusqlite::ffi::SQLITE_BUSY),
            Some("secret SQL must not escape".into()),
        );
        let f = Failure::classify(&sql);
        assert_eq!(f.code, "ATLAS_STORAGE_BUSY");
        assert!(!f.payload().to_string().contains("secret"));
    }
}
