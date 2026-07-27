#!/usr/bin/env ruby
# frozen_string_literal: true

CANONICAL_MODEL_PATH = "src/main/java/com/williamcallahan/javachat/config/ModelConfiguration.java"
EXPECTED_CHAT_MODEL = "gpt-5.4"
SCAN_EXCLUSIONS =
  %r{\A(?:src/test/|config/ast-grep(?:-tests)?/|scripts/lint/check-chat-model-ssot\.rb\z)}
OPENAI_MODEL_ASSIGNMENT = /OPENAI_MODEL\s*[:=]\s*([A-Za-z0-9_.\/-]+)/

def violations_for_text(text, canonical_model)
  text.scan(OPENAI_MODEL_ASSIGNMENT)
      .flatten
      .reject { |model_token| model_token == canonical_model }
end

def run_self_test
  raise "valid canonical model rejected" unless violations_for_text("OPENAI_MODEL=gpt-5.4", EXPECTED_CHAT_MODEL).empty?
  raise "stale model accepted" unless violations_for_text("OPENAI_MODEL=gpt-5", EXPECTED_CHAT_MODEL) == ["gpt-5"]
  qualified_model = "provider/gpt-5.4"
  unless violations_for_text("OPENAI_MODEL=#{qualified_model}", EXPECTED_CHAT_MODEL) == [qualified_model]
    raise "provider-qualified model accepted"
  end
end

if ARGV == ["--self-test"]
  run_self_test
  puts "chat model SSOT self-test passed"
  exit 0
end

canonical_source = File.read(CANONICAL_MODEL_PATH)
canonical_match = canonical_source.match(/DEFAULT_MODEL\s*=\s*"([^"]+)"/)
abort("Unable to derive the canonical chat model from #{CANONICAL_MODEL_PATH}") unless canonical_match

canonical_model = canonical_match[1]
abort("Canonical chat model must remain #{EXPECTED_CHAT_MODEL}, found #{canonical_model}") unless canonical_model == EXPECTED_CHAT_MODEL

tracked_paths = IO.popen(%w[git ls-files -z], "rb", &:read).split("\0")
findings = tracked_paths.each_with_object([]) do |tracked_path, tracked_findings|
  next if tracked_path.match?(SCAN_EXCLUSIONS) || !File.file?(tracked_path)

  file_text = File.binread(tracked_path).force_encoding(Encoding::UTF_8)
  next unless file_text.valid_encoding?

  violations = violations_for_text(file_text, canonical_model)
  next if violations.empty?

  tracked_findings << "#{tracked_path}: #{violations.uniq.join(", ")}"
end

unless findings.empty?
  warn "[LM1a-c] Obsolete or provider-qualified GPT-5 chat-model identifiers detected:"
  findings.each { |finding| warn "  #{finding}" }
  exit 1
end

puts "Tracked chat model references match #{canonical_model}"
