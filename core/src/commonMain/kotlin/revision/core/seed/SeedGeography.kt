package revision.core.seed

// Source: gcse_revision_guides_contents.md section 4 (AQA Geography, Oxford).
//
// Only the chapters on this student's course are seeded (confirmed with the user):
//   Paper 1 B: Ch.8 Cold environments      (NOT Ch.7 Hot deserts)
//   Paper 1 C: Ch.10 Coastal + Ch.11 River (NOT Ch.12 Glacial)
//   Paper 2 C: Ch.22 Energy management     (NOT Ch.20 Food, Ch.21 Water)
//
// Page numbers: chapters 1-11 up to p.78 are HIGH confidence. Everything the source hedges
// on ("~", "approx.") is left null rather than recording a number that may mislead.
// Chapters 13, 14, 16, 19, 23 and 24 are described in the source as running prose rather
// than numbered sub-topics; the sub-topics below are split out from that prose.

val geographySeed = SeedSubject(
    key = "geography", name = "Geography", colour = "#00ACC1", examBoard = "AQA",
    topics = listOf(
        group(
            "Unit 1 — Living with the physical environment",
            group(
                "Section A — The challenge of natural hazards",
                group(
                    "Natural hazards",
                    leaf("What are natural hazards?", "1.1", 15),
                    code = "1",
                ),
                group(
                    "Tectonic hazards",
                    leaf("Plate tectonics theory", "2.1", 16),
                    leaf("Distribution of earthquakes and volcanoes", "2.2", 17),
                    leaf("Physical processes at plate margins", "2.3", 18),
                    leaf("The effects of earthquakes", "2.4", 19),
                    leaf("Responses to earthquakes", "2.5", 20),
                    leaf("Living with the risks from tectonic hazards", "2.6", 21),
                    leaf("Reducing the risks from tectonic hazards", "2.7", 22),
                    leaf("Skills Focus: Dispersion graphs", "2.8", 23),
                    code = "2",
                ),
                group(
                    "Weather hazards",
                    leaf("Global atmospheric circulation", "3.1", 24),
                    leaf("Where are tropical storms formed?", "3.2", 25),
                    leaf("The formation and structure of tropical storms", "3.3", 26),
                    leaf("How might climate change affect tropical storms?", "3.4", 27),
                    leaf("Example: Cyclone Idai — a tropical storm", "3.5", 28),
                    leaf("Reducing the effects of tropical storms", "3.6", 29),
                    leaf("Weather hazards in the UK", "3.7", 30),
                    leaf("Extreme weather in the UK", "3.8", 31),
                    leaf("The Somerset Levels floods, 2014", "3.9", 32),
                    leaf("Skills Focus: OS map (1:25000) and photo skills", "3.10", 33),
                    code = "3",
                ),
                group(
                    "Climate change",
                    leaf("What is the evidence for climate change?", "4.1", 34),
                    leaf("Natural causes of climate change", "4.2", 35),
                    leaf("Human causes of climate change", "4.3", 36),
                    leaf("Managing climate change – mitigation", "4.4", 37),
                    leaf("Managing climate change – adaptation", "4.5", 38),
                    leaf("Skills Focus: Graphs and charts", "4.6", 39),
                    code = "4",
                ),
                page = 14,
            ),
            group(
                "Section B — The living world",
                group(
                    "Ecosystems",
                    leaf("Example: A small-scale UK ecosystem – freshwater pond", "5.1", 41),
                    leaf("How does change affect ecosystems?", "5.2", 42),
                    leaf("Introducing global ecosystems", "5.3", 43),
                    code = "5",
                ),
                group(
                    "Tropical rainforests",
                    leaf("Physical characteristics of rainforests", "6.1", 44),
                    leaf("Adaptation and biodiversity in rainforests", "6.2", 45),
                    leaf("Case Study: Causes of deforestation in Malaysia", "6.3", 46),
                    leaf("Case Study: Impacts of deforestation in Malaysia", "6.4", 47),
                    leaf("The value of tropical rainforests", "6.5", 48),
                    leaf("Sustainable management of tropical rainforests", "6.6", 49),
                    leaf("Skills Focus: Graphs", "6.7", 50),
                    code = "6",
                ),
                group(
                    "Cold environments",
                    leaf("Physical characteristics of cold environments", "8.1", 57),
                    leaf("Adapting to cold environments (+ Skills Focus: Climate graphs)", "8.2", 58),
                    leaf("Case Study: Opportunities for development in cold environments", "8.3", 59),
                    leaf("Case Study: Challenges of developing cold environments", "8.4", 60),
                    leaf("Value of cold environments as wilderness areas", "8.5", 61),
                    leaf("Managing cold environments", "8.6", 62),
                    code = "8",
                ),
                page = 40,
            ),
            group(
                "Section C — Physical landscapes in the UK",
                group(
                    "UK landscapes",
                    leaf("Skills Focus: The UK's diverse landscapes", "9.1", 64),
                    code = "9",
                ),
                group(
                    "Coastal landscapes",
                    leaf("Wave types and their characteristics", "10.1", 65),
                    leaf("Weathering and mass movement", "10.2", 66),
                    leaf("Coastal processes", "10.3", 67),
                    leaf("Coastal erosion landforms", "10.4", 68),
                    leaf("Coastal deposition landforms", "10.5", 69),
                    leaf("Example: Coastal landforms at Swanage", "10.6", 70),
                    leaf("Skills Focus: Photos and OS maps", "10.7", 71),
                    leaf("Managing coasts – hard engineering", "10.8", 72),
                    leaf("Managing coasts – soft engineering", "10.9", 73),
                    leaf("Managing coasts – managed retreat", "10.10", 74),
                    leaf("Example: Coastal management at Lyme Regis", "10.11", 75),
                    code = "10",
                ),
                group(
                    "River landscapes",
                    leaf("Changes in rivers and their valleys", "11.1", 76),
                    leaf("Fluvial (river) processes", "11.2", 77),
                    leaf("River erosion landforms", "11.3", 78),
                    // From 11.4 onward the source says sub-page numbers are NOT reliably confirmed.
                    leaf("River deposition landforms"),
                    leaf("Example: River Tees"),
                    leaf("Flood risk factors"),
                    leaf("Flood management – hard and soft engineering"),
                    leaf("Example: Banbury"),
                    code = "11",
                ),
                page = 63,
            ),
        ),
        group(
            "Unit 2 — Challenges in the human environment",
            group(
                "Section A — Urban issues and challenges",
                group(
                    "The urban world",
                    leaf("Urbanisation and its rate"),
                    leaf("Rio de Janeiro: social and economic opportunities"),
                    leaf("Rio de Janeiro: managing urban growth"),
                    leaf("Rio de Janeiro: water, sanitation and energy"),
                    leaf("Rio de Janeiro: health and education"),
                    leaf("Rio de Janeiro: social and environmental challenges"),
                    leaf("Rio de Janeiro: planning for the urban poor"),
                    code = "13",
                ),
                group(
                    "Urban change in the UK",
                    leaf("Where people live in the UK"),
                    leaf("Bristol: environmental issues"),
                    leaf("Bristol: opportunities"),
                    leaf("Bristol: challenges"),
                    leaf("Bristol: regeneration"),
                    code = "14",
                ),
            ),
            group(
                "Section B — The changing economic world",
                group(
                    "The development gap",
                    leaf("Global development and how it is measured"),
                    leaf("Demographic Transition Model"),
                    leaf("Uneven development"),
                    leaf("Closing the gap: aid"),
                    leaf("Closing the gap: debt relief and loans"),
                    leaf("Closing the gap: fair trade"),
                    leaf("Closing the gap: tourism"),
                    code = "16",
                ),
                leaf("Nigeria: a newly emerging economy", "17"),
                leaf("The changing UK economy", "18"),
            ),
            group(
                "Section C — The challenge of resource management",
                group(
                    "Resource management",
                    leaf("Global distribution of resources"),
                    leaf("UK supply and demand: energy, food and water"),
                    code = "19",
                ),
                leaf("Energy management", "22"),
            ),
        ),
        group(
            "Unit 3 — Geographical Applications",
            group(
                "Issue evaluation",
                leaf("Preparing for Paper 3 and the mark scheme"),
                leaf("Christchurch earthquakes case study"),
                code = "23",
            ),
            group(
                "Fieldwork",
                leaf("The six enquiry stages"),
                leaf("Developing questions"),
                leaf("Selecting, processing and presenting data"),
                leaf("Reaching conclusions"),
                leaf("6- and 9-mark questions"),
                code = "24",
            ),
        ),
    ),
)
