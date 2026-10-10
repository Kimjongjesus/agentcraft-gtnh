"""Regression checks from the builder's independent implementation inspection."""
import copy
import json
import unittest
from unittest.mock import patch
import zlib

from tools.pack_kb import core, extract
from tools.pack_kb.__main__ import main
from tools.pack_kb.tests.helpers import make_pack, scratch, store
from pathlib import Path
from io import StringIO


class InspectionRegressions(unittest.TestCase):
    def test_chain_alternatives_have_no_mutable_step_aliases(self):
        result = core.resolve(store(), 'sample:widget')
        self.assertGreater(len(result['chains']), 1)
        before = copy.deepcopy(result['chains'][1])
        result['chains'][0]['steps'][0]['recipe_id'] = 'modified'
        self.assertEqual(result['chains'][1], before)

    def test_huge_untrusted_quantity_rejected_before_expansion(self):
        with self.assertRaisesRegex(core.ValidationError, '64-bit'):
            core.resolve(store(), 'sample:widget', amount=10**4000)
        data = store()
        data['recipes'][0]['inputs'][0]['amount'] = 2**63
        with self.assertRaisesRegex(core.ValidationError, '64-bit'):
            core.validate_store(data)

    def test_unclassified_cli(self):
        from tools.pack_kb.tests.helpers import SAMPLE
        with patch('sys.stdout', new_callable=StringIO) as out:
            self.assertEqual(main(['--store', str(SAMPLE / 'store.json'), 'machines', 'unclassified']), 0)
        self.assertEqual([x['id'] for x in json.loads(out.getvalue())], ['sample:bench'])

    def test_archive_errors_normalized(self):
        with scratch() as temp:
            root = Path(temp)
            for failure in (zlib.error('bad stream'), RuntimeError('encrypted'), NotImplementedError('compression')):
                with self.subTest(failure=type(failure).__name__):
                    with patch.object(extract.Extractor, 'run', side_effect=failure):
                        with self.assertRaisesRegex(extract.ExtractionError, 'invalid archive'):
                            extract.extract_pack(root, 'synthetic')

    def test_coverage_names_the_array_and_limits_candidate_denominator(self):
        with scratch() as temp:
            root = Path(temp)
            payloads = make_pack(root)
            data = extract.extract_pack(root, 'synthetic')
            self.assertEqual(data['coverage']['tier_recipe_array'], 'VP')
            self.assertIn('not a denominator', data['coverage']['static_builder_candidates_scope'])
            self.assertTrue(payloads)


if __name__ == '__main__':
    unittest.main()
