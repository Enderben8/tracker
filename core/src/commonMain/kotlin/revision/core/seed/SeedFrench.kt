package revision.core.seed

// Source: PROJECT_SPEC.md section 6.3. AQA French, written from memory rather than from a
// document the user owns — the theme titles should be sanity-checked against the live AQA
// specification. No book, so no page numbers; present as editable suggestions.

val frenchSeed = SeedSubject(
    key = "french", name = "French", colour = "#607D8B", examBoard = "AQA",
    topics = listOf(
        group(
            "Theme 1: People and lifestyle",
            *leaves("Identity & relationships", "Healthy living & lifestyle", "Education & work").toTypedArray(),
        ),
        group(
            "Theme 2: Popular culture",
            *leaves("Free-time activities", "Media & technology", "Celebrations & festivals").toTypedArray(),
        ),
        group(
            "Theme 3: Communication and the world around us",
            *leaves("Travel & tourism", "The environment", "Where people live").toTypedArray(),
        ),
        group(
            "Grammar",
            *leaves(
                "Present tense",
                "Perfect tense",
                "Imperfect tense",
                "Near future & future",
                "Conditional",
                "Subjunctive",
                "Reflexive verbs",
                "Negatives",
                "Questions",
                "Adjectives & agreement",
                "Pronouns (direct, indirect, y, en)",
                "Comparatives & superlatives",
                "Prepositions",
                "Connectives & opinions",
            ).toTypedArray(),
        ),
        group(
            "Exam skills",
            *leaves(
                "Listening",
                "Reading",
                "Translation into English",
                "Translation into French",
                "Speaking role-play",
                "Speaking photo card",
                "Speaking general conversation",
                "Writing",
            ).toTypedArray(),
        ),
    ),
)
