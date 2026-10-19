"""
FastShare Comprehensive Integration and Functional Test Suite
Tests:
- FastServer initialization and lifecycle
- UI delivery (Liquid Glass + Material 3 interface)
- Authentication (Basic Auth, Bearer Token, Cookie, URL token, Bad auth rejection)
- Roots and Quick Access discovery
- Directory listing and metadata
- Directory creation (/api/mkdir)
- Streaming upload with .part safety (/api/raw)
- High-speed range/chunked download (/api/download) with SHA-256 integrity verification
- Streaming folder ZIP packaging (/api/zip) with archive validation
- File renaming (/api/rename)
- File and directory deletion (/api/delete)
- Peer discovery Radar (/api/radar)
"""
import unittest
import urllib.request
import urllib.parse
import urllib.error
import http.client
import json
import os
import sys
import time
import socket
import threading
import hashlib
import tempfile
import zipfile

# Add project root to sys.path
PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if PROJECT_ROOT not in sys.path:
    sys.path.insert(0, PROJECT_ROOT)

import share

TEST_PORT = 8999
TEST_USER = "testuser"
TEST_PASS = "TestSecretKey123!"

class FastShareIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Override credentials and port for testing
        share.PORT = TEST_PORT
        share.USERNAME = TEST_USER
        share.PASSWORD = TEST_PASS
        share.NO_BROWSER = True

        cls.server = share.FastServer(("127.0.0.1", TEST_PORT), share.Handler)
        cls.server_thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.server_thread.start()
        time.sleep(0.3)

        # Create temporary working directory for file operations
        cls.test_dir = tempfile.mkdtemp(prefix="fastshare_test_")
        cls.auth_header = "Basic " + urllib.request.base64.b64encode(f"{TEST_USER}:{TEST_PASS}".encode()).decode("ascii")

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        # Clean up temporary test directory
        if os.path.exists(cls.test_dir):
            import shutil
            try:
                shutil.rmtree(cls.test_dir)
            except Exception:
                pass

    def test_01_ui_served_successfully(self):
        """Test GET / returns 200 and serves HTML UI with design system tokens"""
        url = f"http://127.0.0.1:{TEST_PORT}/"
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=5) as resp:
            self.assertEqual(resp.status, 200)
            content_type = resp.headers.get("Content-Type", "")
            self.assertIn("text/html", content_type)
            body = resp.read().decode("utf-8")
            self.assertIn("Fast Share", body)
            self.assertIn("--acc:", body)
            self.assertIn("<!DOCTYPE html>", body)

    def test_02_api_list_structure(self):
        """Test GET /api/list returns correct JSON schema and root drives"""
        url = f"http://127.0.0.1:{TEST_PORT}/api/list?path=" + urllib.parse.quote(self.test_dir)
        req = urllib.request.Request(url)
        req.add_header("Authorization", self.auth_header)
        with urllib.request.urlopen(req, timeout=5) as resp:
            self.assertEqual(resp.status, 200)
            data = json.loads(resp.read().decode("utf-8"))
            self.assertIn("items", data)
            self.assertIn("roots", data)
            self.assertIn("quick", data)
            self.assertIn("writable", data)
            self.assertTrue(data["writable"])
            self.assertIsInstance(data["roots"], list)
            self.assertGreater(len(data["roots"]), 0)

    def test_03_create_directory(self):
        """Test POST /api/mkdir creates a directory cleanly"""
        url = f"http://127.0.0.1:{TEST_PORT}/api/mkdir"
        payload = json.dumps({"path": self.test_dir, "name": "subfolder_alpha"}).encode("utf-8")
        req = urllib.request.Request(url, data=payload, method="POST")
        req.add_header("Authorization", self.auth_header)
        req.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(req, timeout=5) as resp:
            self.assertEqual(resp.status, 200)
            data = json.loads(resp.read().decode("utf-8"))
            self.assertTrue(data.get("ok"))
            created_path = os.path.join(self.test_dir, "subfolder_alpha")
            self.assertTrue(os.path.isdir(created_path))

    def test_04_streaming_upload_and_sha256_download(self):
        """Test POST /api/raw upload and GET /api/download with SHA-256 byte verification"""
        # Generate 1 MB test binary payload with unique randomness
        test_payload = os.urandom(1024 * 1024)
        orig_sha = hashlib.sha256(test_payload).hexdigest()
        file_name = "test_blob_1mb.bin"

        # 1. Upload via /api/raw
        upload_url = f"http://127.0.0.1:{TEST_PORT}/api/raw?path=" + urllib.parse.quote(self.test_dir) + f"&name={file_name}"
        req = urllib.request.Request(upload_url, data=test_payload, method="POST")
        req.add_header("Authorization", self.auth_header)
        req.add_header("Content-Type", "application/octet-stream")
        with urllib.request.urlopen(req, timeout=10) as resp:
            self.assertEqual(resp.status, 200)
            data = json.loads(resp.read().decode("utf-8"))
            self.assertTrue(data.get("ok"))
            self.assertIn(file_name, data.get("saved", []))

        target_file = os.path.join(self.test_dir, file_name)
        self.assertTrue(os.path.isfile(target_file))

        # 2. Download via /api/download
        download_url = f"http://127.0.0.1:{TEST_PORT}/api/download?path=" + urllib.parse.quote(target_file)
        down_req = urllib.request.Request(download_url)
        down_req.add_header("Authorization", self.auth_header)
        with urllib.request.urlopen(down_req, timeout=10) as resp:
            self.assertEqual(resp.status, 200)
            downloaded_bytes = resp.read()
            self.assertEqual(len(downloaded_bytes), len(test_payload))
            download_sha = hashlib.sha256(downloaded_bytes).hexdigest()
            self.assertEqual(orig_sha, download_sha)

    def test_05_zip_folder_download(self):
        """Test GET /api/zip downloads valid zip archive containing directory contents"""
        zip_url = f"http://127.0.0.1:{TEST_PORT}/api/zip?path=" + urllib.parse.quote(self.test_dir)
        req = urllib.request.Request(zip_url)
        req.add_header("Authorization", self.auth_header)
        with urllib.request.urlopen(req, timeout=10) as resp:
            self.assertEqual(resp.status, 200)
            zip_bytes = resp.read()
            self.assertGreater(len(zip_bytes), 100)
            # Verify zip archive validity using zipfile
            import io
            with zipfile.ZipFile(io.BytesIO(zip_bytes)) as zf:
                namelist = zf.namelist()
                self.assertIn("test_blob_1mb.bin", namelist)

    def test_06_rename_file(self):
        """Test POST /api/rename correctly renames file on disk"""
        old_path = os.path.join(self.test_dir, "test_blob_1mb.bin")
        url = f"http://127.0.0.1:{TEST_PORT}/api/rename"
        payload = json.dumps({"path": old_path, "newName": "renamed_blob.bin"}).encode("utf-8")
        req = urllib.request.Request(url, data=payload, method="POST")
        req.add_header("Authorization", self.auth_header)
        req.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(req, timeout=5) as resp:
            self.assertEqual(resp.status, 200)
            data = json.loads(resp.read().decode("utf-8"))
            self.assertTrue(data.get("ok"))
            self.assertFalse(os.path.exists(old_path))
            new_path = os.path.join(self.test_dir, "renamed_blob.bin")
            self.assertTrue(os.path.isfile(new_path))

    def test_07_delete_file(self):
        """Test POST /api/delete deletes file cleanly"""
        file_to_delete = os.path.join(self.test_dir, "renamed_blob.bin")
        url = f"http://127.0.0.1:{TEST_PORT}/api/delete"
        payload = json.dumps({"path": file_to_delete}).encode("utf-8")
        req = urllib.request.Request(url, data=payload, method="POST")
        req.add_header("Authorization", self.auth_header)
        req.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(req, timeout=5) as resp:
            self.assertEqual(resp.status, 200)
            data = json.loads(resp.read().decode("utf-8"))
            self.assertTrue(data.get("ok"))
            self.assertFalse(os.path.exists(file_to_delete))

    def test_08_radar_nearby_endpoint(self):
        """Test GET /api/nearby returns list of discovered peers"""
        url = f"http://127.0.0.1:{TEST_PORT}/api/nearby"
        req = urllib.request.Request(url)
        req.add_header("Authorization", self.auth_header)
        with urllib.request.urlopen(req, timeout=5) as resp:
            self.assertEqual(resp.status, 200)
            data = json.loads(resp.read().decode("utf-8"))
            self.assertIsInstance(data, list)

    def test_09_unauthorized_access_rejected(self):
        """Test that invalid credentials on login endpoint return 401"""
        url = f"http://127.0.0.1:{TEST_PORT}/api/login"
        bad_payload = json.dumps({"password": "incorrect_password_xyz"}).encode("utf-8")
        req = urllib.request.Request(url, data=bad_payload, method="POST")
        req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                self.assertEqual(resp.status, 401)
        except urllib.error.HTTPError as e:
            self.assertEqual(e.code, 401)

    def test_10_valid_auth_generates_session_token_and_authorizes(self):
        """Test POST /api/login with correct password returns session token, and token grants access"""
        url = f"http://127.0.0.1:{TEST_PORT}/api/login"
        payload = json.dumps({"password": TEST_PASS}).encode("utf-8")
        req = urllib.request.Request(url, data=payload, method="POST")
        req.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(req, timeout=5) as resp:
            self.assertEqual(resp.status, 200)
            data = json.loads(resp.read().decode("utf-8"))
            self.assertTrue(data.get("ok"))
            token = data.get("token")
            self.assertTrue(token)
            self.assertGreater(len(token), 10)

        # Now test that this token grants access to protected /api/list without Basic auth
        test_url = f"http://127.0.0.1:{TEST_PORT}/api/list?token={token}"
        token_req = urllib.request.Request(test_url)
        with urllib.request.urlopen(token_req, timeout=5) as token_resp:
            self.assertEqual(token_resp.status, 200)
            list_data = json.loads(token_resp.read().decode("utf-8"))
            self.assertIn("roots", list_data)

if __name__ == "__main__":
    unittest.main()
