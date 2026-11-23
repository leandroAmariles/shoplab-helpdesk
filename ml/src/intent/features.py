"""Lexical feature extractors (word + character n-gram TF-IDF FeatureUnion) for LAB-118."""

from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.pipeline import FeatureUnion


def build_tfidf_union(
    *,
    word_ngram_range: tuple[int, int] = (1, 2),
    char_ngram_range: tuple[int, int] = (3, 5),
    word_min_df: int = 1,
    char_min_df: int = 2,
) -> FeatureUnion:
    """Build a joblib-serializable FeatureUnion combining word and char_wb TF-IDF vectorizers."""
    word_vectorizer = TfidfVectorizer(
        analyzer="word",
        ngram_range=word_ngram_range,
        min_df=word_min_df,
        sublinear_tf=True,
        lowercase=False,
        token_pattern=r"(?u)\b\w+\b",
    )
    char_vectorizer = TfidfVectorizer(
        analyzer="char_wb",
        ngram_range=char_ngram_range,
        min_df=char_min_df,
        sublinear_tf=True,
        lowercase=False,
    )
    return FeatureUnion(
        transformer_list=[
            ("word", word_vectorizer),
            ("char", char_vectorizer),
        ]
    )
