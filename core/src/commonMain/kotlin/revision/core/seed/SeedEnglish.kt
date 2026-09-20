package revision.core.seed

// Source: docs/PROJECT_SPEC.md section 6.2. The source guides have no contents pages, so the
// seven sub-topics per text are a reasonable GUESS, not a transcription — the UI should
// present them as editable suggestions. The Power and Conflict poems are generated: the
// student owns no guide for the anthology.

private fun textTopics(): Array<SeedTopic> = arrayOf(
    leaf("Plot & structure"),
    leaf("Characters"),
    leaf("Themes"),
    leaf("Context"),
    leaf("Language & dramatic techniques"),
    leaf("Key quotations"),
    leaf("Exam practice"),
)

val englishLiteratureSeed = SeedSubject(
    key = "english-literature", name = "English Literature", colour = "#D81B60", examBoard = "AQA",
    topics = listOf(
        group("Macbeth", *textTopics()),
        group("A Christmas Carol", *textTopics()),
        group("An Inspector Calls", *textTopics()),
        group(
            "Poetry: Power and Conflict",
            leaf("Ozymandias (Shelley)"),
            leaf("London (Blake)"),
            leaf("The Prelude: stealing the boat (Wordsworth)"),
            leaf("My Last Duchess (Browning)"),
            leaf("The Charge of the Light Brigade (Tennyson)"),
            leaf("Exposure (Owen)"),
            leaf("Storm on the Island (Heaney)"),
            leaf("Bayonet Charge (Hughes)"),
            leaf("Remains (Armitage)"),
            leaf("Poppies (Weir)"),
            leaf("War Photographer (Duffy)"),
            leaf("Tissue (Dharker)"),
            leaf("The Emigrée (Rumens)"),
            leaf("Checking Out Me History (Agard)"),
            leaf("Kamikaze (Garland)"),
            leaf("Comparing poems"),
            leaf("Unseen poetry"),
        ),
    ),
)
