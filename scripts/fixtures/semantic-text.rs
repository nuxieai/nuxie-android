// Standalone fixture generator using the runtime's canonical schema keys.
// Based on nux-capi/tests/support/semantic_text.rs; this consumer fixture authors
// TextField directly and uses Unicode labels/run names to exercise JNI UTF-8.
// Run with scripts/fixtures/generate-semantic-text.sh from the SDK root.
fn push_var_uint(bytes: &mut Vec<u8>, mut value: u64) {
    loop {
        let mut byte = (value & 0x7f) as u8;
        value >>= 7;
        if value != 0 {
            byte |= 0x80;
        }
        bytes.push(byte);
        if value == 0 {
            break;
        }
    }
}

fn property_key(type_name: &str, property_name: &str) -> u16 {
    let definition = nuxie_schema::definition_by_name(type_name).expect("semantic-text fixture type");
    definition
        .properties
        .iter()
        .chain(definition.ancestors.iter().flat_map(|ancestor| {
            nuxie_schema::definition_by_name(ancestor)
                .expect("semantic-text fixture ancestor")
                .properties
                .iter()
        }))
        .find(|property| property.name == property_name)
        .expect("semantic-text fixture property")
        .key
        .int
}

fn push_object(bytes: &mut Vec<u8>, type_name: &str, body: impl FnOnce(&mut Vec<u8>)) {
    push_var_uint(
        bytes,
        u64::from(
            nuxie_schema::definition_by_name(type_name)
                .expect("semantic-text fixture type")
                .type_key
                .int,
        ),
    );
    body(bytes);
    push_var_uint(bytes, 0);
}

fn push_uint(bytes: &mut Vec<u8>, type_name: &str, property_name: &str, value: u64) {
    push_var_uint(bytes, u64::from(property_key(type_name, property_name)));
    push_var_uint(bytes, value);
}

fn push_f32(bytes: &mut Vec<u8>, type_name: &str, property_name: &str, value: f32) {
    push_var_uint(bytes, u64::from(property_key(type_name, property_name)));
    bytes.extend_from_slice(&value.to_le_bytes());
}


fn push_string(bytes: &mut Vec<u8>, kind: &str, property: &str, value: &str) {
    push_var_uint(bytes, u64::from(property_key(kind, property)));
    push_var_uint(bytes, value.len() as u64);
    bytes.extend_from_slice(value.as_bytes());
}

pub fn semantic_text_artboard() -> Vec<u8> {
    let mut bytes = b"RIVE".to_vec();
    for value in [7, 0, 9_641, 0] { push_var_uint(&mut bytes, value); }
    push_object(&mut bytes, "Backboard", |_| {});
    push_object(&mut bytes, "Artboard", |bytes| {
        push_f32(bytes, "Artboard", "width", 200.0);
        push_f32(bytes, "Artboard", "height", 100.0);
    });
    push_object(&mut bytes, "Text", |bytes| {
        push_uint(bytes, "Component", "parentId", 0);
        push_f32(bytes, "Text", "width", 100.0);
        push_f32(bytes, "Text", "height", 40.0);
    });
    push_object(&mut bytes, "TextStyle", |bytes| { push_uint(bytes, "Component", "parentId", 1); });
    push_object(&mut bytes, "SemanticData", |bytes| {
        push_uint(bytes, "Component", "parentId", 1);
        push_uint(bytes, "SemanticData", "role", 6);
        push_string(bytes, "SemanticData", "label", "Prénom 👋");
    });
    for name in ["field/名前", "field/other"] {
        push_object(&mut bytes, "TextValueRun", |bytes| {
            push_uint(bytes, "Component", "parentId", 1);
            push_uint(bytes, "TextValueRun", "styleId", 2);
            push_string(bytes, "Component", "name", name);
            push_string(bytes, "TextValueRun", "text", "private field value");
        });
    }
    bytes
}

fn main() {
    let output = std::env::args().nth(1).expect("output .riv path");
    std::fs::write(output, semantic_text_artboard()).expect("write fixture");
}
