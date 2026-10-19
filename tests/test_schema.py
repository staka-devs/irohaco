"""SQLite execution/constraint tests for Irohaco schema (not proof of 3NF).
Run: python -m unittest discover -s tests -v, from Irohaco project folder.
"""
import pathlib
import sqlite3
import unittest

DDL = pathlib.Path(__file__).resolve().parents[1] / 'database' / 'schema.sql'


class SchemaTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.executescript(DDL.read_text(encoding='utf-8'))
        self.db.execute('PRAGMA foreign_keys=ON')
        self.db.execute("INSERT INTO storage_volumes VALUES ('vol1','external_primary','ONLINE')")
        self.db.execute("INSERT INTO media_assets VALUES ('a1','IMAGE',100,NULL)")
        self.db.execute("INSERT INTO media_assets VALUES ('vid1','VIDEO',100,NULL)")
        self.db.execute("INSERT INTO media_revisions VALUES ('ar1','a1','sha-file','sha-visual',100,256,256,NULL)")
        self.db.execute("INSERT INTO media_revisions VALUES ('vr1','vid1','v-file','v-visual',100,1920,1080,30000)")
        self.db.execute("INSERT INTO provider_connections VALUES ('c1','LOCAL_LITERT',NULL,NULL)")
        self.db.execute("INSERT INTO model_profiles VALUES ('m1','c1','embeddinggemma-2','artifact-sha','EmbeddingGemma 2')")
        self.db.execute("INSERT INTO embedding_spaces VALUES ('s1','m1',3,'FLOAT32_LE','L2',NULL,NULL,'pre-sha','space-sha','READY')")
        self.db.execute("INSERT INTO embedding_targets(target_id,asset_id) VALUES ('t1','a1')")

    def tearDown(self):
        self.db.close()

    def test_fk_integrity(self):
        self.assertEqual(self.db.execute('PRAGMA foreign_key_check').fetchall(), [])

    def test_primary_keys_are_not_null(self):
        # SQLite lets a non-INTEGER PRIMARY KEY hold NULL unless NOT NULL is declared; Room also requires it.
        tables = [r[0] for r in self.db.execute("SELECT name FROM sqlite_master WHERE type='table'")]
        nullable = [f'{t}.{c[1]}' for t in tables for c in self.db.execute(f"PRAGMA table_info('{t}')") if c[5] and not c[3]]
        self.assertEqual(nullable, [])

    def test_reject_orphan_media_tag(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO media_tags VALUES ('a1','missing')")

    def test_reject_multiple_embed_target_owners(self):
        self.db.execute("INSERT INTO frame_extraction_profiles VALUES ('p','FIXED_INTERVAL',10000,96,'p-sha')")
        self.db.execute("INSERT INTO video_segments VALUES ('seg','vr1','p',0,30000)")
        self.db.execute("INSERT INTO sampled_frames VALUES ('frame','seg',2000,'f-sha')")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO embedding_targets(target_id,asset_id,frame_id) VALUES ('bad','a1','frame')")

    def test_reject_duplicate_embedding_target(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO embedding_targets(target_id,asset_id) VALUES ('t2','a1')")

    def test_embedding_vector_length(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO embedding_vectors VALUES ('vecbad','s1','t1','r',?,100)", (bytes(8),))
        self.db.execute("INSERT INTO embedding_vectors VALUES ('vec1','s1','t1','r',?,100)", (bytes(12),))
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE embedding_vectors SET vector_blob=? WHERE vector_id='vec1'", (bytes(12),))

    def test_reject_duplicate_embedding_vector(self):
        self.db.execute("INSERT INTO embedding_vectors VALUES ('vec1','s1','t1','r',?,100)", (bytes(12),))
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO embedding_vectors VALUES ('vec2','s1','t1','r',?,101)", (bytes(12),))

    def test_reject_mutating_embedding_space_configuration(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE embedding_spaces SET dimension=4 WHERE space_id='s1'")

    def test_reject_initial_indexing_without_user_action(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO indexing_jobs VALUES ('j1','s1','INITIAL','PLANNED',0,100)")
        self.db.execute("INSERT INTO indexing_jobs VALUES ('j1','s1','INITIAL','PLANNED',1,100)")

    def test_denies_external_by_default(self):
        self.assertEqual(self.db.execute("SELECT count(*) FROM egress_grants WHERE connection_id='c1'").fetchone()[0], 0)
        self.db.execute("INSERT INTO egress_grants VALUES ('c1','RAG','IMAGE_PIXELS',100)")
        self.assertEqual(self.db.execute("SELECT count(*) FROM egress_grants WHERE task_code='EMBEDDING'").fetchone()[0], 0)

    def test_reject_multiple_metadata_override_values(self):
        self.db.execute("INSERT INTO metadata_override_sets(override_id,asset_id,namespace,tag_name,edit_mode,local_revision_no,modified_at) VALUES ('o1','a1','EXIF','GPS','REPLACE',1,1)")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO metadata_override_values(override_id,occurrence,text_value,int_value) VALUES ('o1',0,'old',123)")

    def test_reject_multiple_metadata_scalar_values(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO metadata_observations(revision_id,namespace,tag_name,occurrence,text_value,int_value) VALUES ('ar1','EXIF','ISO',0,'100',100)")

    def test_reject_video_segment_for_image(self):
        self.db.execute("INSERT INTO frame_extraction_profiles VALUES ('p','FIXED_INTERVAL',10000,96,'p-sha')")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO video_segments VALUES ('seg','ar1','p',0,1000)")

    def test_reject_frame_outside_segment(self):
        self.db.execute("INSERT INTO frame_extraction_profiles VALUES ('p','FIXED_INTERVAL',10000,96,'p-sha')")
        self.db.execute("INSERT INTO video_segments VALUES ('seg','vr1','p',0,5000)")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO sampled_frames VALUES ('f','seg',5000,'digest')")

    def test_reject_cross_asset_current_revision(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO asset_current_revisions VALUES ('a1','vr1')")

    def test_smart_albums_are_not_materialized(self):
        self.db.execute("INSERT INTO albums VALUES ('al1','smart','SMART',NULL)")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO album_items VALUES ('al1','a1',0)")

    def test_no_support_asset_id_lists(self):
        self.db.execute("INSERT INTO location_evidence VALUES ('e1','a1','OCR','CANDIDATE',NULL,NULL,'徳島',0.6,'OCR推定')")
        self.db.execute("INSERT INTO location_evidence_support VALUES ('e1','vid1')")
        self.assertEqual(self.db.execute("SELECT supporting_asset_id FROM location_evidence_support WHERE evidence_id='e1'").fetchone()[0], 'vid1')

    def test_search_rule_tree_root_is_separate_relation(self):
        self.db.execute("INSERT INTO search_profiles VALUES ('sp','default','METADATA_ONLY')")
        self.db.execute("INSERT INTO saved_searches VALUES ('saved','landscape','sp')")
        self.db.execute("INSERT INTO search_rule_nodes VALUES ('root',NULL,0,'AND',NULL,NULL,NULL)")
        self.db.execute("INSERT INTO search_rule_nodes VALUES ('child','root',0,'PREDICATE','rating','gte','4')")
        self.db.execute("INSERT INTO saved_search_roots VALUES ('saved','root')")
        self.assertEqual(self.db.execute("SELECT root_node_id FROM saved_search_roots WHERE saved_search_id='saved'").fetchone()[0], 'root')

    def test_search_root_cannot_be_child_node(self):
        self.db.execute("INSERT INTO search_profiles VALUES ('sp','default','METADATA_ONLY')")
        self.db.execute("INSERT INTO saved_searches VALUES ('saved','landscape','sp')")
        self.db.execute("INSERT INTO search_rule_nodes VALUES ('root',NULL,0,'AND',NULL,NULL,NULL)")
        self.db.execute("INSERT INTO search_rule_nodes VALUES ('child','root',0,'PREDICATE','rating','gte','4')")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO saved_search_roots VALUES ('saved','child')")

    def test_updates_cannot_bypass_segment_and_frame_rules(self):
        self.db.execute("INSERT INTO frame_extraction_profiles VALUES ('p','FIXED_INTERVAL',10000,96,'p-sha')")
        self.db.execute("INSERT INTO video_segments VALUES ('seg','vr1','p',0,5000)")
        self.db.execute("INSERT INTO sampled_frames VALUES ('f','seg',4000,'digest')")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE video_segments SET revision_id='ar1' WHERE segment_id='seg'")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE video_segments SET end_ms=2000 WHERE segment_id='seg'")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE sampled_frames SET timestamp_ms=5000 WHERE frame_id='f'")

    def test_album_kind_change_requires_empty_album(self):
        self.db.execute("INSERT INTO albums VALUES ('al1','manual','MANUAL',NULL)")
        self.db.execute("INSERT INTO album_items VALUES ('al1','a1',0)")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE albums SET album_kind='SMART' WHERE album_id='al1'")

    def test_smart_album_rule_requires_smart_album(self):
        self.db.execute("INSERT INTO search_profiles VALUES ('sp','default','METADATA_ONLY')")
        self.db.execute("INSERT INTO saved_searches VALUES ('saved','landscape','sp')")
        self.db.execute("INSERT INTO albums VALUES ('man','manual','MANUAL',NULL)")
        self.db.execute("INSERT INTO albums VALUES ('sm','smart','SMART',NULL)")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO smart_album_rules VALUES ('man','saved')")
        self.db.execute("INSERT INTO smart_album_rules VALUES ('sm','saved')")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE albums SET album_kind='MANUAL' WHERE album_id='sm'")

    def test_hierarchies_reject_self_parent(self):
        self.db.execute("INSERT INTO tags VALUES ('t1','x',NULL)")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE tags SET parent_tag_id='t1' WHERE tag_id='t1'")
        self.db.execute("INSERT INTO albums VALUES ('al1','a','MANUAL',NULL)")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE albums SET parent_album_id='al1' WHERE album_id='al1'")

    def test_saved_search_root_cannot_gain_parent(self):
        self.db.execute("INSERT INTO search_profiles VALUES ('sp','default','METADATA_ONLY')")
        self.db.execute("INSERT INTO saved_searches VALUES ('saved','landscape','sp')")
        self.db.execute("INSERT INTO search_rule_nodes VALUES ('root',NULL,0,'AND',NULL,NULL,NULL)")
        self.db.execute("INSERT INTO search_rule_nodes VALUES ('other',NULL,0,'AND',NULL,NULL,NULL)")
        self.db.execute("INSERT INTO saved_search_roots VALUES ('saved','root')")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE search_rule_nodes SET parent_node_id='other' WHERE rule_node_id='root'")

    def test_annotation_revisions_record_author_and_are_append_only(self):
        self.db.execute("INSERT INTO annotation_subjects(subject_id,asset_id) VALUES ('sub','a1')")
        self.db.execute("INSERT INTO semantic_annotations VALUES ('n1','sub','CONTEXT',1,1,100)")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO semantic_annotation_revisions VALUES ('n1',1,'AI',NULL,'caption',100)")
        self.db.execute("INSERT INTO semantic_annotation_revisions VALUES ('n1',1,'AI','m1','caption',100)")
        self.db.execute("INSERT INTO semantic_annotation_revisions VALUES ('n1',2,'USER',NULL,'edited',101)")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE semantic_annotation_revisions SET text='x' WHERE annotation_id='n1' AND revision_no=1")

    def test_task_codes_are_closed_set(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO egress_grants VALUES ('c1','EMBEDING','QUERY_TEXT',100)")


if __name__ == '__main__':
    unittest.main()
