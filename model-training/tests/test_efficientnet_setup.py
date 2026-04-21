import unittest

from model_training import CLASS_NAMES, NUM_CLASSES, VegetationDataset, create_efficientnet_b0
from model_training.config import TRAIN_VAL_CSV


class EfficientNetSetupTests(unittest.TestCase):
    def test_model_has_four_output_classes(self):
        model = create_efficientnet_b0(pretrained=False)
        self.assertEqual(model.classifier[1].out_features, NUM_CLASSES)

    def test_dataset_reads_first_sample(self):
        dataset = VegetationDataset(TRAIN_VAL_CSV)
        image, label = dataset[0]
        self.assertEqual(tuple(image.shape), (3, 224, 224))
        self.assertIn(CLASS_NAMES[label], CLASS_NAMES)

    def test_dataset_accepts_custom_image_size(self):
        dataset = VegetationDataset(TRAIN_VAL_CSV, image_size=320)
        image, _ = dataset[0]
        self.assertEqual(tuple(image.shape), (3, 320, 320))


if __name__ == "__main__":
    unittest.main()
