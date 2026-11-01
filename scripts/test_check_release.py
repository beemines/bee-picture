import unittest
from check_release import PATTERNS


class DisclosurePatternTests(unittest.TestCase):
    def matches(self, text):
        return [name for name, pattern in PATTERNS.items() if pattern.search(text)]

    def test_captured_signed_urls_are_rejected(self):
        for key in ['session_id', 'sign', 'tk', 'access_token']:
            with self.subTest(key=key):
                url = 'https://example.com/search?' + key + '=' + 'a' * 28
                self.assertIn('captured signed query', self.matches(url))

    def test_environment_placeholders_are_allowed(self):
        self.assertEqual([], self.matches('secretKey: ${COS_SECRET_KEY:}'))
        self.assertEqual([], self.matches('apiKey: ${ALIYUN_AI_API_KEY:}'))

    def test_hardcoded_cloud_key_is_rejected(self):
        sample = 'secretKey = "' + ('x' * 32) + '"'
        self.assertIn('hardcoded credential', self.matches(sample))

    def test_public_docs_and_dynamic_parameters_are_allowed(self):
        self.assertEqual([], self.matches('https://example.com/search?q=landscape'))
        self.assertEqual([], self.matches('String url = args[0];'))


if __name__ == '__main__':
    unittest.main()
