-- Irohaco canonical DDL (initial release; Room database version 1). Every canonical relation targets at least 3NF.
-- Derived FTS/vector ANN structures are not authoritative. For Android production,
-- generate/validate equivalent DDL through Room entities & migrations.
PRAGMA foreign_keys = ON;

-- table: storage_volumes
CREATE TABLE storage_volumes (
  volume_id TEXT NOT NULL PRIMARY KEY,
  provider_volume_name TEXT NOT NULL UNIQUE,
  state TEXT NOT NULL CHECK(state IN ('ONLINE','OFFLINE','INACCESSIBLE'))
);

-- table: media_assets
CREATE TABLE media_assets (
  asset_id TEXT NOT NULL PRIMARY KEY,
  media_kind TEXT NOT NULL CHECK(media_kind IN ('IMAGE','VIDEO')),
  first_seen_at INTEGER NOT NULL,
  tombstoned_at INTEGER
);

-- table: media_sources
CREATE TABLE media_sources (
  source_id TEXT NOT NULL PRIMARY KEY,
  asset_id TEXT NOT NULL REFERENCES media_assets(asset_id) ON DELETE RESTRICT,
  volume_id TEXT NOT NULL REFERENCES storage_volumes(volume_id) ON DELETE RESTRICT,
  media_store_id INTEGER,
  content_uri TEXT NOT NULL,
  display_name TEXT NOT NULL,
  relative_path TEXT,
  mime_type TEXT NOT NULL,
  file_size_bytes INTEGER NOT NULL CHECK(file_size_bytes >= 0),
  observed_modified_at INTEGER,
  access_state TEXT NOT NULL CHECK(access_state IN ('AVAILABLE','OFFLINE','INACCESSIBLE','MISSING_CANDIDATE')),
  UNIQUE(volume_id,media_store_id)
);

-- index: ix_media_sources_asset
CREATE INDEX ix_media_sources_asset ON media_sources(asset_id);

-- index: ix_media_sources_access
CREATE INDEX ix_media_sources_access ON media_sources(access_state);

-- table: media_revisions
CREATE TABLE media_revisions (
  revision_id TEXT NOT NULL PRIMARY KEY,
  asset_id TEXT NOT NULL REFERENCES media_assets(asset_id) ON DELETE RESTRICT,
  file_digest TEXT NOT NULL,
  visual_digest TEXT,
  observed_at INTEGER NOT NULL,
  width_px INTEGER CHECK(width_px IS NULL OR width_px > 0),
  height_px INTEGER CHECK(height_px IS NULL OR height_px > 0),
  duration_ms INTEGER CHECK(duration_ms IS NULL OR duration_ms >= 0)
);
-- Parent key of the (asset_id, revision_id) foreign keys; a named index so Room can see it.
CREATE UNIQUE INDEX ux_media_revisions_asset_revision ON media_revisions(asset_id,revision_id);

-- table: asset_current_revisions
CREATE TABLE asset_current_revisions (
  asset_id TEXT NOT NULL PRIMARY KEY REFERENCES media_assets(asset_id) ON DELETE RESTRICT,
  revision_id TEXT NOT NULL,
  FOREIGN KEY(asset_id,revision_id) REFERENCES media_revisions(asset_id,revision_id) ON DELETE RESTRICT
);

-- table: tags
CREATE TABLE tags (
  tag_id TEXT NOT NULL PRIMARY KEY,
  name TEXT NOT NULL,
  parent_tag_id TEXT REFERENCES tags(tag_id) ON DELETE RESTRICT,
  UNIQUE(parent_tag_id,name),
  CHECK(parent_tag_id IS NULL OR parent_tag_id <> tag_id)
);

-- index: ux_root_tags_name
CREATE UNIQUE INDEX ux_root_tags_name ON tags(name) WHERE parent_tag_id IS NULL;

-- table: media_tags
CREATE TABLE media_tags (
  asset_id TEXT NOT NULL REFERENCES media_assets(asset_id) ON DELETE RESTRICT,
  tag_id TEXT NOT NULL REFERENCES tags(tag_id) ON DELETE RESTRICT,
  PRIMARY KEY(asset_id,tag_id)
);

-- index: ix_media_tags_tag
CREATE INDEX ix_media_tags_tag ON media_tags(tag_id);

-- table: media_ratings
CREATE TABLE media_ratings (
  asset_id TEXT NOT NULL PRIMARY KEY REFERENCES media_assets(asset_id) ON DELETE RESTRICT,
  rating INTEGER CHECK(rating BETWEEN 0 AND 5),
  is_favorite INTEGER NOT NULL DEFAULT 0 CHECK(is_favorite IN (0,1))
);

-- table: albums
CREATE TABLE albums (
  album_id TEXT NOT NULL PRIMARY KEY,
  title TEXT NOT NULL,
  album_kind TEXT NOT NULL CHECK(album_kind IN ('MANUAL','SMART')),
  parent_album_id TEXT REFERENCES albums(album_id) ON DELETE RESTRICT,
  CHECK(parent_album_id IS NULL OR parent_album_id <> album_id)
);

-- table: album_items
CREATE TABLE album_items (
  album_id TEXT NOT NULL REFERENCES albums(album_id) ON DELETE RESTRICT,
  asset_id TEXT NOT NULL REFERENCES media_assets(asset_id) ON DELETE RESTRICT,
  sort_order INTEGER NOT NULL,
  PRIMARY KEY(album_id,asset_id),
  UNIQUE(album_id,sort_order)
);

-- table: metadata_observations
CREATE TABLE metadata_observations (
  revision_id TEXT NOT NULL REFERENCES media_revisions(revision_id) ON DELETE RESTRICT,
  namespace TEXT NOT NULL,
  tag_name TEXT NOT NULL,
  occurrence INTEGER NOT NULL DEFAULT 0 CHECK(occurrence >= 0),
  text_value TEXT,
  int_value INTEGER,
  real_value REAL,
  blob_value BLOB,
  PRIMARY KEY(revision_id,namespace,tag_name,occurrence),
  CHECK((text_value IS NOT NULL)+(int_value IS NOT NULL)+(real_value IS NOT NULL)+(blob_value IS NOT NULL)=1)
);

-- table: frame_extraction_profiles
CREATE TABLE frame_extraction_profiles (
  extraction_profile_id TEXT NOT NULL PRIMARY KEY,
  strategy TEXT NOT NULL CHECK(strategy IN ('FIXED_INTERVAL','SCENE_AWARE')),
  interval_ms INTEGER NOT NULL CHECK(interval_ms > 0),
  max_frames INTEGER NOT NULL CHECK(max_frames > 0),
  profile_digest TEXT NOT NULL UNIQUE
);

-- table: video_segments
CREATE TABLE video_segments (
  segment_id TEXT NOT NULL PRIMARY KEY,
  revision_id TEXT NOT NULL REFERENCES media_revisions(revision_id) ON DELETE RESTRICT,
  extraction_profile_id TEXT NOT NULL REFERENCES frame_extraction_profiles(extraction_profile_id) ON DELETE RESTRICT,
  start_ms INTEGER NOT NULL CHECK(start_ms >= 0),
  end_ms INTEGER NOT NULL,
  CHECK(end_ms > start_ms),
  UNIQUE(revision_id,extraction_profile_id,start_ms,end_ms)
);

-- index: ix_video_segments_revision
CREATE INDEX ix_video_segments_revision ON video_segments(revision_id,start_ms);

-- table: sampled_frames
CREATE TABLE sampled_frames (
  frame_id TEXT NOT NULL PRIMARY KEY,
  segment_id TEXT NOT NULL REFERENCES video_segments(segment_id) ON DELETE RESTRICT,
  timestamp_ms INTEGER NOT NULL CHECK(timestamp_ms >= 0),
  frame_digest TEXT NOT NULL,
  UNIQUE(segment_id,timestamp_ms,frame_digest)
);

-- index: ix_sampled_frames_segment
CREATE INDEX ix_sampled_frames_segment ON sampled_frames(segment_id,timestamp_ms);

-- table: annotation_subjects
CREATE TABLE annotation_subjects (
  subject_id TEXT NOT NULL PRIMARY KEY,
  asset_id TEXT REFERENCES media_assets(asset_id) ON DELETE RESTRICT,
  segment_id TEXT REFERENCES video_segments(segment_id) ON DELETE RESTRICT,
  CHECK((asset_id IS NOT NULL)+(segment_id IS NOT NULL)=1)
);

-- index: ux_annotation_subject_asset
CREATE UNIQUE INDEX ux_annotation_subject_asset ON annotation_subjects(asset_id) WHERE asset_id IS NOT NULL;

-- index: ux_annotation_subject_segment
CREATE UNIQUE INDEX ux_annotation_subject_segment ON annotation_subjects(segment_id) WHERE segment_id IS NOT NULL;

-- table: semantic_annotations
CREATE TABLE semantic_annotations (
  annotation_id TEXT NOT NULL PRIMARY KEY,
  subject_id TEXT NOT NULL REFERENCES annotation_subjects(subject_id) ON DELETE RESTRICT,
  annotation_kind TEXT NOT NULL CHECK(annotation_kind IN ('CONTEXT','VISUAL_DESCRIPTION','KEYWORDS','AI_CAPTION')),
  searchable INTEGER NOT NULL DEFAULT 1 CHECK(searchable IN (0,1)),
  exportable INTEGER NOT NULL DEFAULT 1 CHECK(exportable IN (0,1)),
  created_at INTEGER NOT NULL
);

-- index: ix_semantic_annotations_subject
CREATE INDEX ix_semantic_annotations_subject ON semantic_annotations(subject_id);

-- table: semantic_annotation_revisions
-- Edit history. The current text is the row with the highest revision_no; it is not copied onto semantic_annotations.
CREATE TABLE semantic_annotation_revisions (
  annotation_id TEXT NOT NULL REFERENCES semantic_annotations(annotation_id) ON DELETE RESTRICT,
  revision_no INTEGER NOT NULL CHECK(revision_no >= 1),
  author TEXT NOT NULL CHECK(author IN ('USER','AI')),
  model_profile_id TEXT REFERENCES model_profiles(model_profile_id) ON DELETE RESTRICT,
  text TEXT NOT NULL,
  modified_at INTEGER NOT NULL,
  PRIMARY KEY(annotation_id,revision_no),
  CHECK((author='AI') = (model_profile_id IS NOT NULL))
);

-- trigger: semantic_annotation_revisions_append_only
CREATE TRIGGER semantic_annotation_revisions_append_only BEFORE UPDATE ON semantic_annotation_revisions
BEGIN SELECT RAISE(ABORT, 'annotation revisions are append-only'); END;

-- table: ocr_observations
CREATE TABLE ocr_observations (
  ocr_id TEXT NOT NULL PRIMARY KEY,
  asset_id TEXT REFERENCES media_assets(asset_id) ON DELETE RESTRICT,
  frame_id TEXT REFERENCES sampled_frames(frame_id) ON DELETE RESTRICT,
  text TEXT NOT NULL,
  engine_revision TEXT NOT NULL,
  CHECK((asset_id IS NOT NULL)+(frame_id IS NOT NULL)=1)
);

-- index: ix_ocr_asset
CREATE INDEX ix_ocr_asset ON ocr_observations(asset_id);

-- index: ix_ocr_frame
CREATE INDEX ix_ocr_frame ON ocr_observations(frame_id);

-- table: location_evidence
CREATE TABLE location_evidence (
  evidence_id TEXT NOT NULL PRIMARY KEY,
  asset_id TEXT NOT NULL REFERENCES media_assets(asset_id) ON DELETE RESTRICT,
  evidence_source TEXT NOT NULL CHECK(evidence_source IN ('EXIF','USER','OCR','NEARBY_MEDIA','AI_INFERENCE')),
  evidence_status TEXT NOT NULL CHECK(evidence_status IN ('CONFIRMED','CANDIDATE','REJECTED','UNKNOWN')),
  latitude REAL CHECK(latitude IS NULL OR latitude BETWEEN -90 AND 90),
  longitude REAL CHECK(longitude IS NULL OR longitude BETWEEN -180 AND 180),
  place_text TEXT,
  confidence REAL CHECK(confidence IS NULL OR confidence BETWEEN 0 AND 1),
  explanation TEXT NOT NULL DEFAULT '',
  CHECK((latitude IS NULL)=(longitude IS NULL))
);

-- index: ix_location_asset_status
CREATE INDEX ix_location_asset_status ON location_evidence(asset_id,evidence_status);

-- table: location_evidence_support
CREATE TABLE location_evidence_support (
  evidence_id TEXT NOT NULL REFERENCES location_evidence(evidence_id) ON DELETE RESTRICT,
  supporting_asset_id TEXT NOT NULL REFERENCES media_assets(asset_id) ON DELETE RESTRICT,
  PRIMARY KEY(evidence_id,supporting_asset_id)
);

-- table: provider_connections
CREATE TABLE provider_connections (
  connection_id TEXT NOT NULL PRIMARY KEY,
  protocol TEXT NOT NULL CHECK(protocol IN ('LOCAL_LITERT','OPENAI_CHAT','OPENAI_RESPONSES','ANTHROPIC_MESSAGES','OPENAI_EMBEDDINGS','GEMINI_EMBED_CONTENT')),
  base_url TEXT,
  credential_ref TEXT
);

-- table: model_profiles
CREATE TABLE model_profiles (
  model_profile_id TEXT NOT NULL PRIMARY KEY,
  connection_id TEXT NOT NULL REFERENCES provider_connections(connection_id) ON DELETE RESTRICT,
  provider_model_id TEXT NOT NULL,
  artifact_revision TEXT,
  display_name TEXT NOT NULL
);

-- table: model_capabilities
CREATE TABLE model_capabilities (
  model_profile_id TEXT NOT NULL REFERENCES model_profiles(model_profile_id) ON DELETE RESTRICT,
  capability_code TEXT NOT NULL,
  PRIMARY KEY(model_profile_id,capability_code)
);

-- table: embedding_spaces
CREATE TABLE embedding_spaces (
  space_id TEXT NOT NULL PRIMARY KEY,
  model_profile_id TEXT NOT NULL REFERENCES model_profiles(model_profile_id) ON DELETE RESTRICT,
  dimension INTEGER NOT NULL CHECK(dimension > 0),
  value_format TEXT NOT NULL DEFAULT 'FLOAT32_LE' CHECK(value_format IN ('FLOAT32_LE')),
  normalization TEXT NOT NULL CHECK(normalization IN ('NONE','L2')),
  query_prefix TEXT,
  corpus_prefix TEXT,
  preprocessing_signature TEXT NOT NULL,
  space_signature TEXT NOT NULL UNIQUE,
  lifecycle_state TEXT NOT NULL CHECK(lifecycle_state IN ('PLANNED','BUILDING','VERIFYING','READY','STALE','FAILED','DELETING'))
);

-- table: embedding_targets
CREATE TABLE embedding_targets (
  target_id TEXT NOT NULL PRIMARY KEY,
  asset_id TEXT REFERENCES media_assets(asset_id) ON DELETE RESTRICT,
  frame_id TEXT REFERENCES sampled_frames(frame_id) ON DELETE RESTRICT,
  annotation_id TEXT REFERENCES semantic_annotations(annotation_id) ON DELETE RESTRICT,
  ocr_id TEXT REFERENCES ocr_observations(ocr_id) ON DELETE RESTRICT,
  CHECK((asset_id IS NOT NULL)+(frame_id IS NOT NULL)+(annotation_id IS NOT NULL)+(ocr_id IS NOT NULL)=1)
);

-- index: ux_embed_target_asset
CREATE UNIQUE INDEX ux_embed_target_asset ON embedding_targets(asset_id) WHERE asset_id IS NOT NULL;

-- index: ux_embed_target_frame
CREATE UNIQUE INDEX ux_embed_target_frame ON embedding_targets(frame_id) WHERE frame_id IS NOT NULL;

-- index: ux_embed_target_annotation
CREATE UNIQUE INDEX ux_embed_target_annotation ON embedding_targets(annotation_id) WHERE annotation_id IS NOT NULL;

-- index: ux_embed_target_ocr
CREATE UNIQUE INDEX ux_embed_target_ocr ON embedding_targets(ocr_id) WHERE ocr_id IS NOT NULL;

-- table: embedding_vectors
CREATE TABLE embedding_vectors (
  vector_id TEXT NOT NULL PRIMARY KEY,
  space_id TEXT NOT NULL REFERENCES embedding_spaces(space_id) ON DELETE RESTRICT,
  target_id TEXT NOT NULL REFERENCES embedding_targets(target_id) ON DELETE RESTRICT,
  source_revision_digest TEXT NOT NULL,
  vector_blob BLOB NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE(space_id,target_id,source_revision_digest)
);

-- index: ix_embed_vectors_space_target
CREATE INDEX ix_embed_vectors_space_target ON embedding_vectors(space_id,target_id);

-- trigger: validate_embedding_vector_length
CREATE TRIGGER validate_embedding_vector_length BEFORE INSERT ON embedding_vectors
BEGIN
  SELECT CASE WHEN length(NEW.vector_blob) <> 4 * (SELECT dimension FROM embedding_spaces WHERE space_id = NEW.space_id)
    THEN RAISE(ABORT, 'embedding vector dimension mismatch') END;
END;

-- table: search_profiles
CREATE TABLE search_profiles (
  search_profile_id TEXT NOT NULL PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  fusion_strategy TEXT NOT NULL CHECK(fusion_strategy IN ('RRF','SINGLE_SPACE','METADATA_ONLY'))
);

-- table: search_profile_spaces
CREATE TABLE search_profile_spaces (
  search_profile_id TEXT NOT NULL REFERENCES search_profiles(search_profile_id) ON DELETE RESTRICT,
  space_id TEXT NOT NULL REFERENCES embedding_spaces(space_id) ON DELETE RESTRICT,
  priority INTEGER NOT NULL CHECK(priority >= 0),
  PRIMARY KEY(search_profile_id,space_id),
  UNIQUE(search_profile_id,priority)
);

-- table: saved_searches
CREATE TABLE saved_searches (
  saved_search_id TEXT NOT NULL PRIMARY KEY,
  name TEXT NOT NULL,
  search_profile_id TEXT NOT NULL REFERENCES search_profiles(search_profile_id) ON DELETE RESTRICT
);

-- table: search_rule_nodes
CREATE TABLE search_rule_nodes (
  rule_node_id TEXT NOT NULL PRIMARY KEY,
  parent_node_id TEXT REFERENCES search_rule_nodes(rule_node_id) ON DELETE RESTRICT,
  sibling_order INTEGER NOT NULL CHECK(sibling_order >= 0),
  node_kind TEXT NOT NULL CHECK(node_kind IN ('AND','OR','NOT','PREDICATE')),
  field_code TEXT,
  comparator TEXT,
  scalar_value TEXT,
  CHECK(parent_node_id IS NULL OR parent_node_id <> rule_node_id),
  CHECK((node_kind='PREDICATE' AND field_code IS NOT NULL AND comparator IS NOT NULL) OR
        (node_kind<>'PREDICATE' AND field_code IS NULL AND comparator IS NULL AND scalar_value IS NULL))
);

-- index: ux_rule_sibling
CREATE UNIQUE INDEX ux_rule_sibling ON search_rule_nodes(parent_node_id,sibling_order) WHERE parent_node_id IS NOT NULL;

-- table: saved_search_roots
CREATE TABLE saved_search_roots (
  saved_search_id TEXT NOT NULL PRIMARY KEY REFERENCES saved_searches(saved_search_id) ON DELETE RESTRICT,
  root_node_id TEXT NOT NULL UNIQUE REFERENCES search_rule_nodes(rule_node_id) ON DELETE RESTRICT
);

-- table: search_rule_set_values
CREATE TABLE search_rule_set_values (
  rule_node_id TEXT NOT NULL REFERENCES search_rule_nodes(rule_node_id) ON DELETE RESTRICT,
  value TEXT NOT NULL,
  PRIMARY KEY(rule_node_id,value)
);

-- table: indexing_jobs
CREATE TABLE indexing_jobs (
  job_id TEXT NOT NULL PRIMARY KEY,
  space_id TEXT NOT NULL REFERENCES embedding_spaces(space_id) ON DELETE RESTRICT,
  job_kind TEXT NOT NULL CHECK(job_kind IN ('INITIAL','INCREMENTAL','REBUILD')),
  state TEXT NOT NULL CHECK(state IN ('PLANNED','RUNNING','PAUSING','PAUSED','VERIFYING','SUCCEEDED','PARTIAL','FAILED','CANCELLED')),
  requested_by_user INTEGER NOT NULL CHECK(requested_by_user IN (0,1)),
  requested_at INTEGER NOT NULL,
  CHECK(job_kind <> 'INITIAL' OR requested_by_user=1)
);

-- table: indexing_job_items
CREATE TABLE indexing_job_items (
  job_id TEXT NOT NULL REFERENCES indexing_jobs(job_id) ON DELETE RESTRICT,
  target_id TEXT NOT NULL REFERENCES embedding_targets(target_id) ON DELETE RESTRICT,
  source_revision_digest TEXT NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('PENDING','RUNNING','COMMITTED','SKIPPED','FAILED')),
  attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts>=0),
  PRIMARY KEY(job_id,target_id,source_revision_digest)
);

-- index: ix_job_items_state
CREATE INDEX ix_job_items_state ON indexing_job_items(job_id,state);

-- table: egress_grants
CREATE TABLE egress_grants (
  connection_id TEXT NOT NULL REFERENCES provider_connections(connection_id) ON DELETE RESTRICT,
  task_code TEXT NOT NULL CHECK(task_code IN ('SEARCH_QUERY','IMAGE_INDEX','VIDEO_INDEX','CAPTION','RAG','OCR_ASSIST')),
  payload_type TEXT NOT NULL CHECK(payload_type IN ('QUERY_TEXT','IMAGE_PIXELS','VIDEO_FRAMES','VIDEO_BYTES','OCR_TEXT','USER_ANNOTATIONS','GPS_EXIF')),
  granted_at INTEGER NOT NULL,
  PRIMARY KEY(connection_id,task_code,payload_type)
);

-- table: task_model_assignments
CREATE TABLE task_model_assignments (
  task_code TEXT NOT NULL PRIMARY KEY CHECK(task_code IN ('SEARCH_QUERY','IMAGE_INDEX','VIDEO_INDEX','CAPTION','RAG','OCR_ASSIST')),
  model_profile_id TEXT NOT NULL REFERENCES model_profiles(model_profile_id) ON DELETE RESTRICT
);

-- table: metadata_write_operations
-- Journal for writing DB-side metadata edits back to the original file.
CREATE TABLE metadata_write_operations (
  operation_id TEXT NOT NULL PRIMARY KEY,
  asset_id TEXT NOT NULL REFERENCES media_assets(asset_id) ON DELETE RESTRICT,
  before_revision_id TEXT,
  after_revision_id TEXT,
  operation_state TEXT NOT NULL CHECK(operation_state IN ('REQUESTED','PERMISSION_PENDING','STAGED','WRITE_PENDING','VERIFYING','COMMITTED','RECOVERY_REQUIRED')),
  backup_artifact_ref TEXT,
  requested_at INTEGER NOT NULL,
  CHECK(operation_state <> 'COMMITTED' OR after_revision_id IS NOT NULL),
  FOREIGN KEY(asset_id,before_revision_id) REFERENCES media_revisions(asset_id,revision_id) ON DELETE RESTRICT,
  FOREIGN KEY(asset_id,after_revision_id) REFERENCES media_revisions(asset_id,revision_id) ON DELETE RESTRICT
);

-- table: catalog_sync_state
CREATE TABLE catalog_sync_state (
  volume_id TEXT NOT NULL PRIMARY KEY REFERENCES storage_volumes(volume_id) ON DELETE RESTRICT,
  media_store_version TEXT,
  last_generation INTEGER CHECK(last_generation IS NULL OR last_generation >= 0),
  last_successful_sync_at INTEGER
);

-- trigger: ensure_video_segment
CREATE TRIGGER ensure_video_segment BEFORE INSERT ON video_segments
BEGIN
  SELECT CASE WHEN (SELECT a.media_kind FROM media_revisions r JOIN media_assets a ON a.asset_id=r.asset_id
                    WHERE r.revision_id=NEW.revision_id) <> 'VIDEO'
    THEN RAISE(ABORT, 'video segment requires video asset') END;
END;

-- trigger: ensure_video_segment_update
CREATE TRIGGER ensure_video_segment_update BEFORE UPDATE OF revision_id ON video_segments
BEGIN
  SELECT CASE WHEN (SELECT a.media_kind FROM media_revisions r JOIN media_assets a ON a.asset_id=r.asset_id
                    WHERE r.revision_id=NEW.revision_id) <> 'VIDEO'
    THEN RAISE(ABORT, 'video segment requires video asset') END;
END;

-- trigger: ensure_segment_keeps_frames
CREATE TRIGGER ensure_segment_keeps_frames BEFORE UPDATE OF start_ms,end_ms ON video_segments
BEGIN
  SELECT CASE WHEN EXISTS(SELECT 1 FROM sampled_frames f WHERE f.segment_id=NEW.segment_id
                          AND (f.timestamp_ms < NEW.start_ms OR f.timestamp_ms >= NEW.end_ms))
    THEN RAISE(ABORT, 'sampled frame outside segment') END;
END;

-- trigger: ensure_frame_inside_segment
CREATE TRIGGER ensure_frame_inside_segment BEFORE INSERT ON sampled_frames
BEGIN
  SELECT CASE WHEN NEW.timestamp_ms < (SELECT start_ms FROM video_segments WHERE segment_id=NEW.segment_id)
            OR NEW.timestamp_ms >= (SELECT end_ms FROM video_segments WHERE segment_id=NEW.segment_id)
    THEN RAISE(ABORT, 'sampled frame outside segment') END;
END;

-- trigger: ensure_frame_inside_segment_update
CREATE TRIGGER ensure_frame_inside_segment_update BEFORE UPDATE OF segment_id,timestamp_ms ON sampled_frames
BEGIN
  SELECT CASE WHEN NEW.timestamp_ms < (SELECT start_ms FROM video_segments WHERE segment_id=NEW.segment_id)
            OR NEW.timestamp_ms >= (SELECT end_ms FROM video_segments WHERE segment_id=NEW.segment_id)
    THEN RAISE(ABORT, 'sampled frame outside segment') END;
END;

-- trigger: ensure_album_manual
CREATE TRIGGER ensure_album_manual BEFORE INSERT ON album_items
BEGIN
  SELECT CASE WHEN (SELECT album_kind FROM albums WHERE album_id=NEW.album_id) <> 'MANUAL'
    THEN RAISE(ABORT, 'cannot store physical membership for SMART album') END;
END;

-- trigger: ensure_album_manual_update
CREATE TRIGGER ensure_album_manual_update BEFORE UPDATE OF album_id ON album_items
BEGIN
  SELECT CASE WHEN (SELECT album_kind FROM albums WHERE album_id=NEW.album_id) <> 'MANUAL'
    THEN RAISE(ABORT, 'cannot store physical membership for SMART album') END;
END;

-- trigger: ensure_album_kind_change
CREATE TRIGGER ensure_album_kind_change BEFORE UPDATE OF album_kind ON albums
WHEN NEW.album_kind <> OLD.album_kind
BEGIN
  SELECT CASE WHEN EXISTS(SELECT 1 FROM album_items WHERE album_id=NEW.album_id)
              OR EXISTS(SELECT 1 FROM smart_album_rules WHERE album_id=NEW.album_id)
    THEN RAISE(ABORT, 'remove members or rule before changing album kind') END;
END;

-- trigger: embedding_vectors_immutable
CREATE TRIGGER embedding_vectors_immutable BEFORE UPDATE ON embedding_vectors
BEGIN SELECT RAISE(ABORT, 'embedding vector immutable; insert new revision'); END;

-- trigger: embedding_spaces_signature_immutable
CREATE TRIGGER embedding_spaces_signature_immutable BEFORE UPDATE OF model_profile_id,dimension,
    value_format,normalization,query_prefix,corpus_prefix,preprocessing_signature,space_signature ON embedding_spaces
BEGIN SELECT RAISE(ABORT, 'embedding space configuration immutable'); END;

-- trigger: ensure_saved_search_root
CREATE TRIGGER ensure_saved_search_root BEFORE INSERT ON saved_search_roots
BEGIN
  SELECT CASE WHEN (SELECT parent_node_id FROM search_rule_nodes WHERE rule_node_id=NEW.root_node_id) IS NOT NULL
    THEN RAISE(ABORT, 'saved search root has parent') END;
END;

-- trigger: ensure_saved_search_root_update
CREATE TRIGGER ensure_saved_search_root_update BEFORE UPDATE OF root_node_id ON saved_search_roots
BEGIN
  SELECT CASE WHEN (SELECT parent_node_id FROM search_rule_nodes WHERE rule_node_id=NEW.root_node_id) IS NOT NULL
    THEN RAISE(ABORT, 'saved search root has parent') END;
END;

-- trigger: ensure_root_node_stays_root
CREATE TRIGGER ensure_root_node_stays_root BEFORE UPDATE OF parent_node_id ON search_rule_nodes
WHEN NEW.parent_node_id IS NOT NULL
BEGIN
  SELECT CASE WHEN EXISTS(SELECT 1 FROM saved_search_roots WHERE root_node_id=NEW.rule_node_id)
    THEN RAISE(ABORT, 'saved search root has parent') END;
END;

-- table: smart_album_rules
-- A SMART album is defined by exactly one saved search; members are computed, never stored.
CREATE TABLE smart_album_rules (
  album_id TEXT NOT NULL PRIMARY KEY REFERENCES albums(album_id) ON DELETE RESTRICT,
  saved_search_id TEXT NOT NULL REFERENCES saved_searches(saved_search_id) ON DELETE RESTRICT
);

-- trigger: ensure_smart_album_rule
CREATE TRIGGER ensure_smart_album_rule BEFORE INSERT ON smart_album_rules
BEGIN
  SELECT CASE WHEN (SELECT album_kind FROM albums WHERE album_id=NEW.album_id) <> 'SMART'
    THEN RAISE(ABORT, 'rule requires SMART album') END;
END;

-- trigger: ensure_smart_album_rule_update
CREATE TRIGGER ensure_smart_album_rule_update BEFORE UPDATE OF album_id ON smart_album_rules
BEGIN
  SELECT CASE WHEN (SELECT album_kind FROM albums WHERE album_id=NEW.album_id) <> 'SMART'
    THEN RAISE(ABORT, 'rule requires SMART album') END;
END;

-- table: metadata_override_sets
CREATE TABLE metadata_override_sets (
  override_id TEXT NOT NULL PRIMARY KEY,
  asset_id TEXT NOT NULL REFERENCES media_assets(asset_id) ON DELETE RESTRICT,
  namespace TEXT NOT NULL,
  tag_name TEXT NOT NULL,
  edit_mode TEXT NOT NULL CHECK(edit_mode IN ('REPLACE','REMOVE')),
  local_revision_no INTEGER NOT NULL CHECK(local_revision_no > 0),
  modified_at INTEGER NOT NULL,
  UNIQUE (asset_id,namespace,tag_name)
);

-- table: metadata_override_values
CREATE TABLE metadata_override_values (
  override_id TEXT NOT NULL REFERENCES metadata_override_sets(override_id) ON DELETE RESTRICT,
  occurrence INTEGER NOT NULL CHECK(occurrence >= 0),
  text_value TEXT,
  int_value INTEGER,
  real_value REAL,
  blob_value BLOB,
  PRIMARY KEY (override_id,occurrence),
  CHECK((text_value IS NOT NULL)+(int_value IS NOT NULL)+
        (real_value IS NOT NULL)+(blob_value IS NOT NULL)=1)
);

-- trigger: metadata_override_value_requires_replace
CREATE TRIGGER metadata_override_value_requires_replace BEFORE INSERT ON metadata_override_values
BEGIN
  SELECT CASE WHEN (SELECT edit_mode FROM metadata_override_sets
     WHERE override_id=NEW.override_id) <> 'REPLACE'
    THEN RAISE(ABORT,'metadata value requires REPLACE') END;
END;

-- trigger: metadata_override_set_no_remove_with_values
CREATE TRIGGER metadata_override_set_no_remove_with_values BEFORE UPDATE OF edit_mode ON metadata_override_sets
WHEN NEW.edit_mode='REMOVE'
BEGIN
  SELECT CASE WHEN EXISTS(SELECT 1 FROM metadata_override_values v WHERE v.override_id=NEW.override_id)
    THEN RAISE(ABORT,'delete values before REMOVE') END;
END;

-- table: metadata_sync_baselines
CREATE TABLE metadata_sync_baselines (
  override_id TEXT NOT NULL PRIMARY KEY REFERENCES metadata_override_sets(override_id) ON DELETE RESTRICT,
  base_revision_id TEXT NOT NULL REFERENCES media_revisions(revision_id) ON DELETE RESTRICT,
  base_local_revision_no INTEGER NOT NULL CHECK(base_local_revision_no > 0),
  checked_at INTEGER NOT NULL
);

-- table: metadata_conflicts
CREATE TABLE metadata_conflicts (
  conflict_id TEXT NOT NULL PRIMARY KEY,
  override_id TEXT NOT NULL REFERENCES metadata_override_sets(override_id) ON DELETE RESTRICT,
  base_revision_id TEXT NOT NULL REFERENCES media_revisions(revision_id) ON DELETE RESTRICT,
  remote_revision_id TEXT NOT NULL REFERENCES media_revisions(revision_id) ON DELETE RESTRICT,
  observed_local_revision_no INTEGER NOT NULL CHECK(observed_local_revision_no > 0),
  detected_at INTEGER NOT NULL,
  resolution_code TEXT CHECK(resolution_code IN ('KEEP_LOCAL','ACCEPT_REMOTE','MANUAL_MERGE')),
  resolved_at INTEGER,
  CHECK((resolution_code IS NULL)=(resolved_at IS NULL)),
  CHECK(base_revision_id <> remote_revision_id),
  UNIQUE(override_id,base_revision_id,remote_revision_id,observed_local_revision_no)
);

-- index: ix_metadata_conflicts_open
CREATE INDEX ix_metadata_conflicts_open ON metadata_conflicts(override_id,detected_at) WHERE resolved_at IS NULL;

-- trigger: metadata_baseline_revision_owner
CREATE TRIGGER metadata_baseline_revision_owner BEFORE INSERT ON metadata_sync_baselines
BEGIN
  SELECT CASE WHEN (SELECT a.asset_id FROM metadata_override_sets a WHERE a.override_id=NEW.override_id)
         <> (SELECT r.asset_id FROM media_revisions r WHERE r.revision_id=NEW.base_revision_id)
    THEN RAISE(ABORT,'baseline revision belongs to different asset') END;
END;

-- trigger: metadata_conflict_revision_owner
CREATE TRIGGER metadata_conflict_revision_owner BEFORE INSERT ON metadata_conflicts
BEGIN
  SELECT CASE WHEN (SELECT a.asset_id FROM metadata_override_sets a WHERE a.override_id=NEW.override_id)
          <> (SELECT r.asset_id FROM media_revisions r WHERE r.revision_id=NEW.base_revision_id)
         OR (SELECT a.asset_id FROM metadata_override_sets a WHERE a.override_id=NEW.override_id)
          <> (SELECT r.asset_id FROM media_revisions r WHERE r.revision_id=NEW.remote_revision_id)
    THEN RAISE(ABORT,'conflict revision belongs to different asset') END;
END;

-- table: media_discoveries
CREATE TABLE media_discoveries (
  discovery_id TEXT NOT NULL PRIMARY KEY,
  volume_id TEXT NOT NULL REFERENCES storage_volumes(volume_id) ON DELETE RESTRICT,
  provider_media_store_id INTEGER,
  observed_uri TEXT NOT NULL,
  observed_size_bytes INTEGER NOT NULL CHECK(observed_size_bytes >= 0),
  observed_at INTEGER NOT NULL,
  discovery_state TEXT NOT NULL CHECK(discovery_state IN ('UNRESOLVED','RESOLVED','DISMISSED'))
);

-- index: ix_discovery_volume
CREATE INDEX ix_discovery_volume ON media_discoveries(volume_id,provider_media_store_id);

-- table: identity_candidates
CREATE TABLE identity_candidates (
  discovery_id TEXT NOT NULL REFERENCES media_discoveries(discovery_id) ON DELETE RESTRICT,
  asset_id TEXT NOT NULL REFERENCES media_assets(asset_id) ON DELETE RESTRICT,
  confidence REAL NOT NULL CHECK(confidence BETWEEN 0 AND 1),
  candidate_state TEXT NOT NULL CHECK(candidate_state IN ('PENDING','REJECTED','ACCEPTED')),
  PRIMARY KEY(discovery_id,asset_id)
);

-- table: identity_candidate_signals
CREATE TABLE identity_candidate_signals (
  discovery_id TEXT NOT NULL,
  asset_id TEXT NOT NULL,
  signal_code TEXT NOT NULL CHECK(signal_code IN ('SOURCE_CONTINUITY','CONTENT_SHA256','VISUAL_HASH','EXIF_TIME','DIMENSIONS','FILE_NAME','USER_CONFIRMATION')),
  signal_digest TEXT,
  PRIMARY KEY(discovery_id,asset_id,signal_code),
  FOREIGN KEY(discovery_id,asset_id) REFERENCES identity_candidates(discovery_id,asset_id) ON DELETE RESTRICT
);

-- table: identity_resolutions
CREATE TABLE identity_resolutions (
  discovery_id TEXT NOT NULL PRIMARY KEY REFERENCES media_discoveries(discovery_id) ON DELETE RESTRICT,
  resolution_kind TEXT NOT NULL CHECK(resolution_kind IN ('LINK_EXISTING','NEW_ASSET','IGNORE')),
  selected_asset_id TEXT REFERENCES media_assets(asset_id) ON DELETE RESTRICT,
  decided_by TEXT NOT NULL CHECK(decided_by IN ('USER','HIGH_CONFIDENCE_POLICY','IMPORT')),
  resolved_at INTEGER NOT NULL,
  CHECK((resolution_kind='IGNORE' AND selected_asset_id IS NULL) OR
        (resolution_kind IN ('LINK_EXISTING','NEW_ASSET') AND selected_asset_id IS NOT NULL))
);

-- trigger: identity_no_auto_digest_merge
CREATE TRIGGER identity_no_auto_digest_merge BEFORE INSERT ON identity_resolutions
WHEN NEW.resolution_kind='LINK_EXISTING' AND NEW.decided_by='HIGH_CONFIDENCE_POLICY'
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM identity_candidate_signals s
    WHERE s.discovery_id=NEW.discovery_id AND s.asset_id=NEW.selected_asset_id
      AND s.signal_code='SOURCE_CONTINUITY'
  ) THEN RAISE(ABORT,'automatic linking requires source continuity') END;
END;

-- trigger: identity_selected_candidate_required
CREATE TRIGGER identity_selected_candidate_required BEFORE INSERT ON identity_resolutions
WHEN NEW.resolution_kind='LINK_EXISTING'
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM identity_candidates c
    WHERE c.discovery_id=NEW.discovery_id AND c.asset_id=NEW.selected_asset_id AND c.candidate_state <> 'REJECTED'
  ) THEN RAISE(ABORT,'link must be a non-rejected candidate') END;
END;

-- table: media_access_events
CREATE TABLE media_access_events (
  access_event_id TEXT NOT NULL PRIMARY KEY,
  asset_id TEXT REFERENCES media_assets(asset_id) ON DELETE RESTRICT,
  source_id TEXT REFERENCES media_sources(source_id) ON DELETE RESTRICT,
  access_event_code TEXT NOT NULL CHECK(access_event_code IN ('GRANTED','REVOKED','PARTIAL','SOURCE_OFFLINE','SOURCE_RECONNECTED','OPEN_DENIED')),
  observed_at INTEGER NOT NULL,
  CHECK((asset_id IS NOT NULL)+(source_id IS NOT NULL)=1)
);

-- index: ix_access_events_asset
CREATE INDEX ix_access_events_asset ON media_access_events(asset_id,observed_at);

-- table: asset_privacy_rules
CREATE TABLE asset_privacy_rules (
  asset_id TEXT NOT NULL PRIMARY KEY REFERENCES media_assets(asset_id) ON DELETE RESTRICT,
  allow_ai_indexing INTEGER NOT NULL DEFAULT 1 CHECK(allow_ai_indexing IN (0,1)),
  allow_remote_send INTEGER NOT NULL DEFAULT 0 CHECK(allow_remote_send IN (0,1)),
  updated_at INTEGER NOT NULL
);

-- table: backup_archives
CREATE TABLE backup_archives (
  backup_id TEXT NOT NULL PRIMARY KEY,
  format_version INTEGER NOT NULL CHECK(format_version > 0),
  created_at INTEGER NOT NULL,
  archive_ref TEXT NOT NULL,
  manifest_sha256 TEXT NOT NULL,
  encryption_code TEXT NOT NULL CHECK(encryption_code IN ('AES_256_GCM')),
  state TEXT NOT NULL CHECK(state IN ('STAGING','VERIFIED','FAILED'))
);

-- table: backup_archive_entries
CREATE TABLE backup_archive_entries (
  backup_id TEXT NOT NULL REFERENCES backup_archives(backup_id) ON DELETE RESTRICT,
  entity_kind TEXT NOT NULL,
  exported_entity_id TEXT NOT NULL,
  content_sha256 TEXT NOT NULL,
  PRIMARY KEY(backup_id,entity_kind,exported_entity_id)
);

-- table: restore_sessions
CREATE TABLE restore_sessions (
  restore_id TEXT NOT NULL PRIMARY KEY,
  backup_id TEXT NOT NULL REFERENCES backup_archives(backup_id) ON DELETE RESTRICT,
  restore_state TEXT NOT NULL CHECK(restore_state IN ('STAGING','VALIDATING','PREVIEW','APPLYING','VERIFYING','COMMITTED','FAILED','ROLLED_BACK')),
  started_at INTEGER NOT NULL,
  completed_at INTEGER
);

-- table: restore_asset_bindings
CREATE TABLE restore_asset_bindings (
  restore_id TEXT NOT NULL REFERENCES restore_sessions(restore_id) ON DELETE RESTRICT,
  exported_asset_id TEXT NOT NULL,
  local_asset_id TEXT REFERENCES media_assets(asset_id) ON DELETE RESTRICT,
  match_decision TEXT NOT NULL CHECK(match_decision IN ('MATCHED','NEW_PLACEHOLDER','SKIPPED','NEEDS_REVIEW')),
  PRIMARY KEY(restore_id,exported_asset_id),
  CHECK((match_decision IN ('MATCHED','NEW_PLACEHOLDER') AND local_asset_id IS NOT NULL) OR
        (match_decision IN ('SKIPPED','NEEDS_REVIEW') AND local_asset_id IS NULL))
);

-- index: ix_restore_bindings_target
CREATE INDEX ix_restore_bindings_target ON restore_asset_bindings(local_asset_id);

-- table: metadata_write_operation_items
-- Which tag edits (at which local revision) a write-back operation carries, so recovery can replay or verify it.
CREATE TABLE metadata_write_operation_items (
  operation_id TEXT NOT NULL REFERENCES metadata_write_operations(operation_id) ON DELETE RESTRICT,
  override_id TEXT NOT NULL REFERENCES metadata_override_sets(override_id) ON DELETE RESTRICT,
  written_local_revision_no INTEGER NOT NULL CHECK(written_local_revision_no > 0),
  PRIMARY KEY(operation_id,override_id)
);

-- trigger: metadata_write_item_same_asset
CREATE TRIGGER metadata_write_item_same_asset BEFORE INSERT ON metadata_write_operation_items
BEGIN
  SELECT CASE WHEN (SELECT asset_id FROM metadata_write_operations WHERE operation_id=NEW.operation_id)
         <> (SELECT asset_id FROM metadata_override_sets WHERE override_id=NEW.override_id)
    THEN RAISE(ABORT,'write item belongs to different asset') END;
END;

-- trigger: metadata_write_item_same_asset_update
CREATE TRIGGER metadata_write_item_same_asset_update BEFORE UPDATE OF operation_id,override_id ON metadata_write_operation_items
BEGIN
  SELECT CASE WHEN (SELECT asset_id FROM metadata_write_operations WHERE operation_id=NEW.operation_id)
         <> (SELECT asset_id FROM metadata_override_sets WHERE override_id=NEW.override_id)
    THEN RAISE(ABORT,'write item belongs to different asset') END;
END;

-- trigger: metadata_override_value_requires_replace_update
CREATE TRIGGER metadata_override_value_requires_replace_update BEFORE UPDATE OF override_id ON metadata_override_values
BEGIN
  SELECT CASE WHEN (SELECT edit_mode FROM metadata_override_sets WHERE override_id=NEW.override_id) <> 'REPLACE'
    THEN RAISE(ABORT,'metadata value requires REPLACE') END;
END;

-- trigger: metadata_baseline_revision_owner_update
CREATE TRIGGER metadata_baseline_revision_owner_update BEFORE UPDATE OF override_id,base_revision_id ON metadata_sync_baselines
BEGIN
  SELECT CASE WHEN (SELECT a.asset_id FROM metadata_override_sets a WHERE a.override_id=NEW.override_id)
         <> (SELECT r.asset_id FROM media_revisions r WHERE r.revision_id=NEW.base_revision_id)
    THEN RAISE(ABORT,'baseline revision belongs to different asset') END;
END;

-- trigger: metadata_conflict_revision_owner_update
CREATE TRIGGER metadata_conflict_revision_owner_update BEFORE UPDATE OF override_id,base_revision_id,remote_revision_id ON metadata_conflicts
BEGIN
  SELECT CASE WHEN (SELECT a.asset_id FROM metadata_override_sets a WHERE a.override_id=NEW.override_id)
          <> (SELECT r.asset_id FROM media_revisions r WHERE r.revision_id=NEW.base_revision_id)
         OR (SELECT a.asset_id FROM metadata_override_sets a WHERE a.override_id=NEW.override_id)
          <> (SELECT r.asset_id FROM media_revisions r WHERE r.revision_id=NEW.remote_revision_id)
    THEN RAISE(ABORT,'conflict revision belongs to different asset') END;
END;

-- trigger: identity_no_auto_digest_merge_update
CREATE TRIGGER identity_no_auto_digest_merge_update BEFORE UPDATE OF resolution_kind,selected_asset_id,decided_by ON identity_resolutions
WHEN NEW.resolution_kind='LINK_EXISTING' AND NEW.decided_by='HIGH_CONFIDENCE_POLICY'
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM identity_candidate_signals s
    WHERE s.discovery_id=NEW.discovery_id AND s.asset_id=NEW.selected_asset_id
      AND s.signal_code='SOURCE_CONTINUITY'
  ) THEN RAISE(ABORT,'automatic linking requires source continuity') END;
END;

-- trigger: identity_selected_candidate_required_update
CREATE TRIGGER identity_selected_candidate_required_update BEFORE UPDATE OF resolution_kind,selected_asset_id ON identity_resolutions
WHEN NEW.resolution_kind='LINK_EXISTING'
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM identity_candidates c
    WHERE c.discovery_id=NEW.discovery_id AND c.asset_id=NEW.selected_asset_id AND c.candidate_state <> 'REJECTED'
  ) THEN RAISE(ABORT,'link must be a non-rejected candidate') END;
END;

-- trigger: metadata_write_operation_asset_immutable
CREATE TRIGGER metadata_write_operation_asset_immutable BEFORE UPDATE OF asset_id ON metadata_write_operations
BEGIN SELECT RAISE(ABORT,'operation asset is immutable'); END;

-- trigger: metadata_override_set_asset_immutable
CREATE TRIGGER metadata_override_set_asset_immutable BEFORE UPDATE OF asset_id ON metadata_override_sets
BEGIN SELECT RAISE(ABORT,'override asset is immutable'); END;
