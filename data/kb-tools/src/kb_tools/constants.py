"""Fixed rules of the knowledge-base corpus (see specs/LAB-18-kb-policies-faqs-corpus/data-model.md)."""

TOPICS = ("shipping", "returns", "warranty", "payments", "privacy")
DOC_TYPES = ("policy", "faq")
LANGUAGE = "es"

MIN_DOCS = 20
MAX_DOCS = 30
MIN_PER_TOPIC = 3

ALLOWED_EMAIL_DOMAIN = "shoplab.example"
FILENAME_PATTERN = r"^(shipping|returns|warranty|payments|privacy)-\d{2}-[a-z0-9-]+$"
CONTROL_FILES = ("INDEX.md", "REVIEW.md")
FRONT_MATTER_FIELDS = ("title", "topic", "type", "language", "scope")

PDF_DOCUMENT_IDS = (
    "shipping-01-delivery-times",
    "shipping-02-shipping-costs",
    "returns-01-return-policy",
    "returns-03-refunds",
    "warranty-01-legal-warranty",
    "warranty-02-extended-warranty",
    "payments-01-payment-methods",
    "payments-02-installments",
    "privacy-01-privacy-policy",
    "privacy-03-data-rights",
)
