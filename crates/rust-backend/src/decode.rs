use std::error::Error;
use std::fs;

use ndarray::{Array2, Axis};

pub fn load_vocab(path: &str) -> Result<Vec<String>, Box<dyn Error>> {
    let content = fs::read_to_string(path)?;
    let mut label_map: Vec<String> = Vec::new();

    for (label_id, line) in content.lines().enumerate() {
        if label_id == 0 {
            continue;
        }
        if let Some(token) = line.split('\t').next() {
            label_map.push(token.to_string());
        }
    }

    Ok(label_map)
}

pub fn ctc_greedy_decode(
    logits: &Array2<f32>,
    blank_id: i64,
    label_map: &[String],
) -> Result<String, Box<dyn Error>> {
    let blank_base: i64 = 5120;

    let predictions: Vec<i64> = logits
        .axis_iter(Axis(0))
        .map(|row| {
            row.iter()
                .enumerate()
                .max_by(|(_, a), (_, b)| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal))
                .map(|(idx, _)| idx as i64)
                .unwrap_or(blank_id)
        })
        .collect();

    let mut decoded: Vec<String> = Vec::new();
    let mut previous = blank_id;

    for &prediction in &predictions {
        if prediction != blank_id && prediction != previous {
            let raw_idx = prediction - blank_base;
            let vocab_idx = raw_idx - 1;
            if vocab_idx >= 0 && (vocab_idx as usize) < label_map.len() {
                decoded.push(label_map[vocab_idx as usize].clone());
            }
        }
        previous = prediction;
    }

    let text = decoded.concat().replace('\u{2581}', " ").trim().to_string();

    Ok(text)
}

/// CTC greedy decode over a restricted set of output classes, for
/// multilingual models where each language owns a slice of the shared CTC
/// head. `mask` holds the class indices belonging to the language (in vocab
/// order, with the shared `blank_id` class as its last member); `vocab` holds
/// that language's tokens, entry i matching mask position i.
pub fn ctc_greedy_decode_masked(
    logits: &Array2<f32>,
    mask: &[usize],
    blank_id: i64,
    vocab: &[String],
) -> Result<String, Box<dyn Error>> {
    if mask.is_empty() {
        return Err("empty language mask".into());
    }

    let mut decoded: Vec<&str> = Vec::new();
    let mut previous: i64 = blank_id;

    for row in logits.axis_iter(Axis(0)) {
        let mut best_pos = 0usize;
        let mut best_val = f32::NEG_INFINITY;
        for (pos, &class) in mask.iter().enumerate() {
            let value = row[class];
            if value > best_val {
                best_val = value;
                best_pos = pos;
            }
        }

        let class = mask[best_pos] as i64;
        if class != blank_id && class != previous {
            if let Some(token) = vocab.get(best_pos) {
                // <unk> is a special token, never part of the transcript.
                if !token.is_empty() && token != "<unk>" {
                    decoded.push(token);
                }
            }
        }
        previous = class;
    }

    let text = decoded.concat().replace('\u{2581}', " ").trim().to_string();

    Ok(text)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn row_matrix(rows: &[[f32; 4]]) -> Array2<f32> {
        Array2::from_shape_vec(
            (rows.len(), 4),
            rows.iter().flat_map(|r| r.to_vec()).collect(),
        )
        .unwrap()
    }

    #[test]
    fn test_masked_decode_basic() {
        // Classes 0..3; language owns [0, 1] plus the shared blank 3.
        let vocab = vec!["▁a".to_string(), "b".to_string()];
        let mask = vec![0usize, 1, 3];

        // Frames: blank, b, b, blank, a, a, b  ->  "b" + "▁a" + "b"  ->  "b ab"
        let logits = row_matrix(&[
            [0.0, 0.0, 0.0, 5.0], // blank
            [0.0, 9.0, 0.0, 0.0], // b
            [0.0, 9.0, 0.0, 0.0], // b (repeat, collapsed)
            [1.0, 0.0, 0.0, 5.0], // blank (separates the two b's)
            [7.0, 0.0, 0.0, 0.0], // a
            [7.0, 0.0, 0.0, 0.0], // a (repeat, collapsed)
            [0.0, 3.0, 0.0, 0.0], // b
        ]);

        let text = ctc_greedy_decode_masked(&logits, &mask, 3, &vocab).unwrap();
        assert_eq!(text, "b ab");
    }

    #[test]
    fn test_masked_decode_ignores_non_member_classes() {
        // Class 2 has the globally highest logit but is not in the mask.
        let vocab = vec!["x".to_string(), "y".to_string()];
        let mask = vec![0usize, 1, 3];

        let logits = row_matrix(&[
            [0.0, 0.0, 100.0, 0.0], // class 2 wins globally, but is masked out -> x
            [0.0, 8.0, 100.0, 0.0], // y
        ]);

        let text = ctc_greedy_decode_masked(&logits, &mask, 3, &vocab).unwrap();
        assert_eq!(text, "xy");
    }

    #[test]
    fn test_masked_decode_skips_unk() {
        let vocab = vec!["<unk>".to_string(), "y".to_string()];
        let mask = vec![0usize, 1, 3];

        let logits = row_matrix(&[
            [9.0, 0.0, 0.0, 0.0], // unk
            [0.0, 9.0, 0.0, 0.0], // y
        ]);

        let text = ctc_greedy_decode_masked(&logits, &mask, 3, &vocab).unwrap();
        assert_eq!(text, "y");
    }

    #[test]
    fn test_masked_decode_all_blank() {
        let vocab = vec!["x".to_string(), "y".to_string()];
        let mask = vec![0usize, 1, 3];

        let logits = row_matrix(&[[0.0, 0.0, 0.0, 9.0], [0.0, 0.0, 0.0, 9.0]]);

        let text = ctc_greedy_decode_masked(&logits, &mask, 3, &vocab).unwrap();
        assert_eq!(text, "");
    }
}
