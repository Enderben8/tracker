package revision.core.seed

// Source: gcse_revision_guides_contents.md section 2.
// Titles are reliable; page numbers are LOW confidence in the source, so they are left null.
// The per-section "Exam Questions" / "Revision Questions" rows are practice material, not
// topics, and would only add noise to the revise-next queue, so they are omitted.
// Exam board is null: the source only says "OCR-style" (open question in the spec).

val computerScienceSeed = SeedSubject(
    key = "computer-science", name = "Computer Science", colour = "#5E35B1", examBoard = null,
    topics = listOf(
        group(
            "Component 01 — Computer Systems",
            group(
                "Section One — Components of a Computer System",
                *leaves(
                    "Computer Systems",
                    "CPU and System Performance",
                    "Memory",
                    "Secondary Storage",
                    "Systems Software – The OS",
                    "Systems Software – Utilities",
                ).toTypedArray(),
            ),
            group(
                "Section Two — Data Representation",
                *leaves(
                    "Units",
                    "Binary Numbers",
                    "Hexadecimal Numbers",
                    "Characters",
                    "Storing Images",
                    "Storing Sound",
                    "Compression",
                ).toTypedArray(),
            ),
            group(
                "Section Three — Networks",
                *leaves(
                    "Networks – LANs and WANs",
                    "Networks – Hardware",
                    "Wireless Networks",
                    "Network Topologies",
                    "Client-server and Peer-to-Peer Networks",
                    "Network Protocols",
                    "Networks – The Internet",
                    "Network Security Threats",
                ).toTypedArray(),
            ),
            group(
                "Section Four — Issues",
                *leaves(
                    "Ethical and Cultural Issues",
                    "Computer Legislation",
                    "Environmental Issues",
                    "Open Source and Proprietary Software",
                ).toTypedArray(),
            ),
        ),
        group(
            "Component 02 — Computational Thinking, Algorithms and Programming",
            group(
                "Section Five — Algorithms",
                *leaves(
                    "Computational Thinking",
                    "Writing Algorithms – Pseudocode",
                    "Writing Algorithms – Flowcharts",
                    "Search Algorithms",
                    "Sorting Algorithms",
                ).toTypedArray(),
            ),
            group(
                "Section Six — Programming",
                *leaves(
                    "Programming Basics – Data Types",
                    "Programming Basics – Casting and Operators",
                    "Programming Basics – Operators",
                    "Constants and Variables",
                    "Strings",
                    "Program Flow",
                    "Boolean Logic",
                    "Random Number Generation",
                ).toTypedArray(),
            ),
            group(
                "Section Seven — Design, Testing and IDEs",
                *leaves(
                    "Structured Programming",
                    "Defensive Design",
                    "Testing",
                    "Trace Tables",
                    "Translators",
                    "Integrated Development Environments",
                ).toTypedArray(),
            ),
        ),
    ),
)
