"""Metadata conflict, identity, access and backup invariants; not a formal proof of 3NF."""
import pathlib
import sqlite3
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCHEMA = (ROOT / 'database/schema.sql').read_text(encoding='utf-8')


class IntegrityTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.executescript(SCHEMA)
        self.db.execute("INSERT INTO storage_volumes VALUES ('vol','external_primary','ONLINE')")
        self.db.execute("INSERT INTO media_assets VALUES ('img','IMAGE',1,NULL)")
        self.db.execute("INSERT INTO media_assets VALUES ('copy','IMAGE',1,NULL)")
        self.db.execute("INSERT INTO media_revisions VALUES ('r1','img','digest','visual',1,100,100,NULL)")
        self.db.execute("INSERT INTO media_revisions VALUES ('r2','img','digest2','visual',2,100,100,NULL)")
        self.db.execute("INSERT INTO media_revisions VALUES ('rc','copy','digest','visual',1,100,100,NULL)")
        self.db.execute("INSERT INTO metadata_override_sets(override_id,asset_id,namespace,tag_name,edit_mode,local_revision_no,modified_at) VALUES ('ov-desc','img','EXIF','Description','REPLACE',1,10)")
        self.db.execute("INSERT INTO metadata_override_values(override_id,occurrence,text_value) VALUES ('ov-desc',0,'original edit')")
        self.db.execute("INSERT INTO metadata_override_sets(override_id,asset_id,namespace,tag_name,edit_mode,local_revision_no,modified_at) VALUES ('ov-gps','img','EXIF','GPS','REMOVE',1,12)")

    def tearDown(self):
        self.db.close()

    def expect_integrity(self, sql, params=()):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(sql, params)

    def test_override_set_replace_and_remove_rows(self):
        rows = self.db.execute("SELECT tag_name,edit_mode FROM metadata_override_sets ORDER BY tag_name").fetchall()
        self.assertEqual(rows, [('Description', 'REPLACE'), ('GPS', 'REMOVE')])
        text = self.db.execute("SELECT text_value FROM metadata_override_values WHERE override_id='ov-desc'").fetchone()
        self.assertEqual(text, ('original edit',))
        self.assertEqual(self.db.execute("SELECT count(*) FROM metadata_override_values").fetchone()[0], 1)
        self.assertEqual(self.db.execute('PRAGMA foreign_key_check').fetchall(), [])

    def test_multiple_values_are_scalar_rows(self):
        self.db.execute("INSERT INTO metadata_override_values(override_id,occurrence,text_value) VALUES ('ov-desc',1,'second')")
        self.assertEqual(self.db.execute("SELECT count(*) FROM metadata_override_values WHERE override_id='ov-desc'").fetchone()[0],2)
        self.expect_integrity("INSERT INTO metadata_override_values(override_id,occurrence,text_value) VALUES ('ov-desc',1,'dup')")

    def test_remove_disallows_values_and_set_with_values_disallows_remove(self):
        self.expect_integrity("INSERT INTO metadata_override_values(override_id,occurrence,text_value) VALUES ('ov-gps',0,'bad')")
        self.expect_integrity("UPDATE metadata_override_sets SET edit_mode='REMOVE' WHERE override_id='ov-desc'")

    def test_baseline_cannot_point_to_another_asset(self):
        self.expect_integrity("INSERT INTO metadata_sync_baselines VALUES ('ov-desc','rc',1,10)")
        self.db.execute("INSERT INTO metadata_sync_baselines VALUES ('ov-desc','r1',1,10)")

    def test_conflict_cross_asset_revision_rejected(self):
        self.expect_integrity("INSERT INTO metadata_conflicts(conflict_id,override_id,base_revision_id,remote_revision_id,observed_local_revision_no,detected_at) VALUES ('bad','ov-desc','r1','rc',1,12)")
        self.db.execute("INSERT INTO metadata_conflicts(conflict_id,override_id,base_revision_id,remote_revision_id,observed_local_revision_no,detected_at) VALUES ('ok','ov-desc','r1','r2',1,12)")
        self.expect_integrity("UPDATE metadata_conflicts SET resolution_code='KEEP_LOCAL' WHERE conflict_id='ok'")
        self.db.execute("UPDATE metadata_conflicts SET resolution_code='KEEP_LOCAL',resolved_at=16 WHERE conflict_id='ok'")

    def test_auto_link_requires_source_continuity(self):
        self.db.execute("INSERT INTO media_discoveries VALUES ('d','vol',10,'content://test',300,5,'UNRESOLVED')")
        self.db.execute("INSERT INTO identity_candidates VALUES ('d','img',0.98,'PENDING')")
        self.db.execute("INSERT INTO identity_candidate_signals VALUES ('d','img','CONTENT_SHA256','hash')")
        self.expect_integrity("INSERT INTO identity_resolutions VALUES ('d','LINK_EXISTING','img','HIGH_CONFIDENCE_POLICY',9)")
        self.db.execute("INSERT INTO identity_candidate_signals VALUES ('d','img','SOURCE_CONTINUITY','source-id')")
        self.db.execute("INSERT INTO identity_resolutions VALUES ('d','LINK_EXISTING','img','HIGH_CONFIDENCE_POLICY',9)")

    def test_ambiguous_digest_not_auto_merge(self):
        self.db.execute("INSERT INTO media_discoveries VALUES ('d','vol',10,'content://test',300,5,'UNRESOLVED')")
        self.db.execute("INSERT INTO identity_candidates VALUES ('d','img',0.99,'PENDING')")
        self.db.execute("INSERT INTO identity_candidates VALUES ('d','copy',0.99,'PENDING')")
        self.expect_integrity("INSERT INTO identity_resolutions VALUES ('d','LINK_EXISTING','copy','HIGH_CONFIDENCE_POLICY',9)")
        self.db.execute("INSERT INTO identity_resolutions VALUES ('d','LINK_EXISTING','copy','USER',9)")

    def test_access_event_not_permission_truth(self):
        self.db.execute("INSERT INTO media_access_events VALUES ('e','img',NULL,'REVOKED',5)")
        self.expect_integrity("INSERT INTO media_access_events VALUES ('invalid',NULL,NULL,'REVOKED',5)")
        self.assertEqual(self.db.execute("SELECT count(*) FROM media_access_events").fetchone()[0],1)

    def test_backup_manifest_fk_and_restore_binding(self):
        self.db.execute("INSERT INTO backup_archives VALUES ('b',1,10,'content://archive','sha','AES_256_GCM','VERIFIED')")
        self.db.execute("INSERT INTO backup_archive_entries VALUES ('b','media_asset','img','shaasset')")
        self.db.execute("INSERT INTO restore_sessions VALUES ('s','b','PREVIEW',12,NULL)")
        self.expect_integrity("INSERT INTO restore_asset_bindings VALUES ('s','original',NULL,'MATCHED')")
        self.db.execute("INSERT INTO restore_asset_bindings VALUES ('s','original','img','MATCHED')")
        self.assertEqual(self.db.execute('PRAGMA foreign_key_check').fetchall(), [])

    def test_write_journal_cross_asset_revision_rejected(self):
        self.expect_integrity("INSERT INTO metadata_write_operations VALUES ('op','img','rc',NULL,'REQUESTED',NULL,15)")
        self.db.execute("INSERT INTO metadata_write_operations VALUES ('op','img','r1',NULL,'REQUESTED',NULL,15)")
        self.expect_integrity("UPDATE metadata_write_operations SET after_revision_id='rc' WHERE operation_id='op'")

    def test_write_journal_commit_requires_verified_revision(self):
        self.db.execute("INSERT INTO metadata_write_operations VALUES ('op','img','r1',NULL,'VERIFYING',NULL,15)")
        self.expect_integrity("UPDATE metadata_write_operations SET operation_state='COMMITTED' WHERE operation_id='op'")
        self.db.execute("UPDATE metadata_write_operations SET operation_state='COMMITTED',after_revision_id='r2' WHERE operation_id='op'")

    def test_write_journal_items_belong_to_same_asset(self):
        self.db.execute("INSERT INTO metadata_override_sets(override_id,asset_id,namespace,tag_name,edit_mode,local_revision_no,modified_at) VALUES ('ov-copy','copy','EXIF','Description','REMOVE',1,10)")
        self.db.execute("INSERT INTO metadata_write_operations VALUES ('op','img','r1',NULL,'REQUESTED',NULL,15)")
        self.expect_integrity("INSERT INTO metadata_write_operation_items VALUES ('op','ov-copy',1)")
        self.db.execute("INSERT INTO metadata_write_operation_items VALUES ('op','ov-desc',1)")
        self.expect_integrity("UPDATE metadata_write_operations SET asset_id='copy' WHERE operation_id='op'")

    def test_all_tables_have_primary_keys_and_fk_integrity(self):
        tables = [x[0] for x in self.db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")]
        self.assertGreaterEqual(len(tables), 50)
        for table in tables:
            self.assertTrue(any(x[5] for x in self.db.execute(f'PRAGMA table_info("{table}")')),table)
        self.assertEqual(self.db.execute('PRAGMA foreign_key_check').fetchall(), [])

    def test_update_paths_cannot_bypass_conflict_and_identity_ownership(self):
        self.db.execute("INSERT INTO metadata_sync_baselines VALUES ('ov-desc','r1',1,10)")
        self.expect_integrity("UPDATE metadata_sync_baselines SET base_revision_id='rc' WHERE override_id='ov-desc'")
        self.db.execute("INSERT INTO media_discoveries VALUES ('d','vol',10,'content://test',300,5,'UNRESOLVED')")
        self.db.execute("INSERT INTO identity_candidates VALUES ('d','img',0.99,'PENDING')")
        self.db.execute("INSERT INTO identity_resolutions VALUES ('d','LINK_EXISTING','img','USER',9)")
        self.expect_integrity("UPDATE identity_resolutions SET decided_by='HIGH_CONFIDENCE_POLICY' WHERE discovery_id='d'")


if __name__ == '__main__':
    unittest.main()
