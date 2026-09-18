package revision.core.seed

// Source: gcse_revision_guides_contents.md section 1 (HIGH confidence).
// The "reference" rows (Periodic Table p.222, Physics equations p.224) are not revisable topics.

val biologySeed = SeedSubject(
    key = "biology", name = "Biology", colour = "#43A047", examBoard = "AQA",
    topics = listOf(
        leaf("Cell biology", "B1", 2),
        leaf("Cell transport", "B2", 12),
        leaf("Cell division", "B3", 24),
        leaf("Organisation in animals", "B4", 34),
        leaf("Enzymes", "B5", 46),
        leaf("Organisation in plants", "B6", 60),
        leaf("The spread of diseases", "B7", 72),
        leaf("Preventing and treating disease", "B8", 84),
        leaf("Monoclonal antibodies", "B9", 96),
        leaf("Non-communicable diseases", "B10", 106),
        leaf("Photosynthesis", "B11", 118),
        leaf("Respiration", "B12", 130),
        leaf("Nervous system & homeostasis", "B13", 142),
        leaf("Hormonal coordination", "B14", 156),
        leaf("Variation", "B15", 168),
        leaf("Reproduction", "B16", 180),
        leaf("Evolution", "B17", 194),
        leaf("Adaptation", "B18", 208),
        leaf("Organising an ecosystem", "B19", 220),
        leaf("Humans and biodiversity", "B20", 232),
    ),
)

val chemistrySeed = SeedSubject(
    key = "chemistry", name = "Chemistry", colour = "#F57C00", examBoard = "AQA",
    topics = listOf(
        leaf("The atom", "C1", 2),
        leaf("Covalent bonding", "C2", 14),
        leaf("Ionic bonding, metallic bonding, and structure", "C3", 26),
        leaf("The Periodic Table", "C4", 38),
        leaf("Transition metals and nanoparticles", "C5", 48),
        leaf("Chemical calculations with mass", "C6", 58),
        leaf("Chemical calculations with moles", "C7", 68),
        leaf("Reactions of metals", "C8", 78),
        leaf("Reactions of acids", "C9", 88),
        leaf("Electrolysis", "C10", 98),
        leaf("Energy changes", "C11", 110),
        leaf("Rate of reaction", "C12", 120),
        leaf("Equilibrium", "C13", 132),
        leaf("Crude oil and fuels", "C14", 142),
        leaf("Organic reactions", "C15", 152),
        leaf("Polymers", "C16", 162),
        leaf("Chemical analysis", "C17", 172),
        leaf("The Earth's atmosphere", "C18", 184),
        leaf("Using the Earth's resources", "C19", 194),
        leaf("Making our resources", "C20", 208),
    ),
)

val physicsSeed = SeedSubject(
    key = "physics", name = "Physics", colour = "#1E88E5", examBoard = "AQA",
    topics = listOf(
        leaf("Energy stores and transfers", "P1", 2),
        leaf("Energy transfers by heating", "P2", 14),
        leaf("National and global energy resources", "P3", 26),
        leaf("Supplying energy", "P4", 36),
        leaf("Electric circuits", "P5", 48),
        leaf("Energy of matter", "P6", 60),
        leaf("Atoms", "P7", 72),
        leaf("Radiation", "P8", 84),
        leaf("Forces", "P9", 96),
        leaf("Pressure in liquids and gases", "P10", 108),
        leaf("Speed", "P11", 116),
        leaf("Newton's laws of motion", "P12", 128),
        leaf("Braking and momentum", "P13", 140),
        leaf("Mechanical waves", "P14", 152),
        leaf("Electromagnetic waves", "P15", 162),
        leaf("Light and sound", "P16", 174),
        leaf("Magnets and electromagnets", "P17", 186),
        leaf("Induced potential and transformers", "P18", 200),
        leaf("Space", "P19", 212),
    ),
)
