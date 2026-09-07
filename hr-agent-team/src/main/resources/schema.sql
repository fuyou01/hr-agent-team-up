CREATE TABLE IF NOT EXISTS interview_session (
  session_id VARCHAR(128) PRIMARY KEY,
  state_json TEXT NOT NULL,
  status VARCHAR(64) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS interview_event (
  session_id VARCHAR(128) NOT NULL,
  event_id VARCHAR(128) NOT NULL,
  client_event_id VARCHAR(255) NOT NULL,
  server_sequence BIGINT NOT NULL,
  client_sequence BIGINT,
  event_type VARCHAR(128) NOT NULL,
  event_json TEXT NOT NULL,
  occurred_at VARCHAR(64) NOT NULL,
  PRIMARY KEY (session_id, client_event_id)
);
CREATE INDEX IF NOT EXISTS idx_interview_event_session_seq ON interview_event(session_id, server_sequence);

CREATE TABLE IF NOT EXISTS media_chunk (
  session_id VARCHAR(128) NOT NULL,
  media_id VARCHAR(255) NOT NULL,
  chunk_no INT NOT NULL,
  sha256 VARCHAR(64) NOT NULL,
  storage_ref VARCHAR(2048) NOT NULL,
  upload_status VARCHAR(32) NOT NULL,
  codec VARCHAR(64),
  start_ms BIGINT NOT NULL,
  end_ms BIGINT NOT NULL,
  merged_ref VARCHAR(2048),
  merged_sha256 VARCHAR(64),
  PRIMARY KEY (session_id, media_id, chunk_no)
);
CREATE INDEX IF NOT EXISTS idx_media_chunk_session_media ON media_chunk(session_id, media_id, chunk_no);
