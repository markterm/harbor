//! Portable v1 `.fm1save` files, byte-compatible with fm1-emu's browser (web/persistence.js) and
//! plugin (crates/fm1-plugin/src/savefile.rs), from which this is adapted.
use base64::Engine;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

pub const SECTOR_SIZE: usize = 4096;
pub const FLASH_SIZE: usize = fm1_soc::FLASH_SIZE;
pub const MAX_PACKAGE: usize = 8 << 20;
pub const MAX_SAVE: usize = 16 << 20;
const FORMAT: &str = "fm1-emulator-save";

/// A flash sector the firmware wrote.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Sector {
    pub at: u32,
    #[serde(with = "b64")]
    pub data: Vec<u8>,
}

#[derive(Clone, Debug)]
pub struct SaveData {
    pub name: String,
    pub pkg: Vec<u8>,
    pub sectors: Vec<Sector>,
}

#[derive(Serialize, Deserialize)]
struct Envelope {
    format: String,
    version: u32,
    hash: String,
    name: String,
    #[serde(default, with = "optional_b64")]
    package: Option<Vec<u8>>,
    sectors: Vec<Sector>,
}

mod b64 {
    use super::*;
    pub fn serialize<S: serde::Serializer>(v: &[u8], s: S) -> Result<S::Ok, S::Error> {
        base64::engine::general_purpose::STANDARD.encode(v).serialize(s)
    }
    pub fn deserialize<'de, D: serde::Deserializer<'de>>(d: D) -> Result<Vec<u8>, D::Error> {
        base64::engine::general_purpose::STANDARD.decode(String::deserialize(d)?).map_err(serde::de::Error::custom)
    }
}

mod optional_b64 {
    use super::*;
    pub fn serialize<S: serde::Serializer>(v: &Option<Vec<u8>>, s: S) -> Result<S::Ok, S::Error> {
        v.as_ref().map(|p| base64::engine::general_purpose::STANDARD.encode(p)).serialize(s)
    }
    pub fn deserialize<'de, D: serde::Deserializer<'de>>(d: D) -> Result<Option<Vec<u8>>, D::Error> {
        Option::<String>::deserialize(d)?
            .map(|s| base64::engine::general_purpose::STANDARD.decode(s).map_err(serde::de::Error::custom))
            .transpose()
    }
}

pub fn validate_sectors(sectors: &[Sector]) -> Result<(), String> {
    let mut seen = [false; FLASH_SIZE / SECTOR_SIZE];
    if sectors.len() > seen.len() {
        return Err("too many flash sectors".into());
    }
    for sector in sectors {
        let at = sector.at as usize;
        if at >= FLASH_SIZE || at % SECTOR_SIZE != 0 || sector.data.len() != SECTOR_SIZE {
            return Err("invalid flash sector offset or length".into());
        }
        if std::mem::replace(&mut seen[at / SECTOR_SIZE], true) {
            return Err("duplicate flash sector".into());
        }
    }
    Ok(())
}

fn validate(data: &SaveData) -> Result<(), String> {
    if !(20 * 0x30..=MAX_PACKAGE).contains(&data.pkg.len()) {
        return Err("invalid firmware package size".into());
    }
    if data.name.encode_utf16().count() > 256 {
        return Err("save name too long".into());
    }
    validate_sectors(&data.sectors)
}

/// SHA-256 of a whole package, lower-case hex: a firmware build's identity.
pub fn hash(pkg: &[u8]) -> String {
    Sha256::digest(pkg).iter().map(|b| format!("{b:02x}")).collect()
}

/// Decode a save. `fallback`: the package to use when the save has none (bring-your-own
/// firmware); its hash must match the save's.
pub fn decode(bytes: &[u8], fallback: Option<&[u8]>) -> Result<SaveData, String> {
    if bytes.len() > MAX_SAVE {
        return Err("save file too large".into());
    }
    let envelope: Envelope = serde_json::from_slice(bytes).map_err(|e| format!("invalid .fm1save: {e}"))?;
    if envelope.format != FORMAT || envelope.version != 1 {
        return Err("unsupported emulator save format or version".into());
    }
    let pkg = envelope.package.or_else(|| fallback.map(<[u8]>::to_vec))
        .ok_or("open the matching .fwsc firmware first, then import this save again")?;
    let data = SaveData { name: envelope.name, pkg, sectors: envelope.sectors };
    validate(&data)?;
    if hash(&data.pkg) != envelope.hash {
        return Err("firmware hash mismatch".into());
    }
    Ok(data)
}

/// The hash a save names, without validating the rest (to find the package it needs).
pub fn peek_hash(bytes: &[u8]) -> Option<String> {
    #[derive(Deserialize)]
    struct Head { hash: String }
    serde_json::from_slice::<Head>(bytes).ok().map(|h| h.hash)
}

/// Encode a save. `include_package`: false for bring-your-own firmware, which may not be shared.
pub fn encode(data: &SaveData, include_package: bool) -> Result<Vec<u8>, String> {
    validate(data)?;
    serde_json::to_vec(&Envelope {
        format: FORMAT.into(),
        version: 1,
        hash: hash(&data.pkg),
        name: data.name.clone(),
        package: include_package.then(|| data.pkg.clone()),
        sectors: data.sectors.clone(),
    })
    .map_err(|e| format!("could not encode save: {e}"))
}

/// Harbor's own sector store: `[u32 offset LE][4096 bytes]` records, compact and quick to write.
pub fn sectors_to_bytes(sectors: &[Sector]) -> Vec<u8> {
    let mut out = Vec::with_capacity(sectors.len() * (4 + SECTOR_SIZE));
    for s in sectors {
        out.extend_from_slice(&s.at.to_le_bytes());
        out.extend_from_slice(&s.data);
    }
    out
}

pub fn sectors_from_bytes(bytes: &[u8]) -> Result<Vec<Sector>, String> {
    if bytes.len() % (4 + SECTOR_SIZE) != 0 {
        return Err("truncated sector store".into());
    }
    let sectors: Vec<Sector> = bytes
        .chunks_exact(4 + SECTOR_SIZE)
        .map(|r| Sector { at: u32::from_le_bytes([r[0], r[1], r[2], r[3]]), data: r[4..].to_vec() })
        .collect();
    validate_sectors(&sectors)?;
    Ok(sectors)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> SaveData {
        SaveData {
            name: "共享 setup 🎹".into(),
            pkg: vec![42; 960],
            sectors: vec![Sector { at: 0xff000, data: vec![0xff; SECTOR_SIZE] }],
        }
    }

    #[test]
    fn round_trip() {
        let original = sample();
        let restored = decode(&encode(&original, true).unwrap(), None).unwrap();
        assert_eq!(restored.pkg, original.pkg);
        assert_eq!(restored.name, original.name);
        assert_eq!(restored.sectors, original.sectors);
    }

    #[test]
    fn package_less_save_requires_the_matching_firmware() {
        let original = sample();
        let bytes = encode(&original, false).unwrap();
        assert!(decode(&bytes, None).is_err());
        assert_eq!(peek_hash(&bytes).unwrap(), hash(&original.pkg));
        assert_eq!(decode(&bytes, Some(&original.pkg)).unwrap().sectors, original.sectors);
        assert!(decode(&bytes, Some(&[0; 960])).is_err());
    }

    #[test]
    fn malformed_files_and_sectors_are_rejected() {
        let original: serde_json::Value = serde_json::from_slice(&encode(&sample(), true).unwrap()).unwrap();
        for field in ["hash", "format", "package"] {
            let mut bad = original.clone();
            bad[field] = "wrong".into();
            assert!(decode(&serde_json::to_vec(&bad).unwrap(), None).is_err());
        }
        let mut future = original.clone();
        future["version"] = 2.into();
        assert!(decode(&serde_json::to_vec(&future).unwrap(), None).is_err());
        let mut duplicate = sample();
        duplicate.sectors.push(duplicate.sectors[0].clone());
        assert!(encode(&duplicate, true).is_err());
        for at in [1, 0x100000, u32::MAX] {
            let mut bad = sample();
            bad.sectors[0].at = at;
            assert!(encode(&bad, true).is_err());
        }
        assert!(decode(b"{}", None).is_err());
    }

    #[test]
    fn accepts_the_browser_generated_v1_fixture() {
        let fixture = include_bytes!("../../../third_party/fm1-emu/crates/fm1-plugin/tests/fixtures/browser-v1.fm1save");
        let data = decode(fixture, None).unwrap();
        assert_eq!(data.name, "Browser shared setup");
        assert_eq!(data.pkg.len(), 960);
        assert_eq!(data.pkg[511], 255);
        assert_eq!(data.sectors[0].at, 0xff000);
        assert!(data.sectors[0].data.iter().all(|&b| b == 0x56));
        let again = decode(&encode(&data, true).unwrap(), None).unwrap();
        assert_eq!(again.pkg, data.pkg);
        assert_eq!(again.sectors, data.sectors);
    }

    #[test]
    fn sector_store_round_trip_and_validation() {
        let sectors = sample().sectors;
        assert_eq!(sectors_from_bytes(&sectors_to_bytes(&sectors)).unwrap(), sectors);
        assert!(sectors_from_bytes(&[0; 5]).is_err());
        let mut bad = sectors_to_bytes(&sectors);
        bad[0] = 1;
        assert!(sectors_from_bytes(&bad).is_err());
    }
}
