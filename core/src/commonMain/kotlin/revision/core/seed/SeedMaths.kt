package revision.core.seed

// Source: docs/PROJECT_SPEC.md section 6.3. AQA Maths, HIGHER tier. There is no book, so no
// page numbers; this is a generated standard topic list and should be presented as
// editable suggestions.

val mathsSeed = SeedSubject(
    key = "maths", name = "Maths", colour = "#FBC02D", examBoard = "AQA",
    topics = listOf(
        group(
            "Number",
            *leaves(
                "Place value & rounding",
                "Factors, multiples & primes",
                "Fractions",
                "Decimals",
                "Percentages",
                "Ratio & proportion",
                "Indices",
                "Standard form",
                "Surds",
                "Bounds & error intervals",
            ).toTypedArray(),
        ),
        group(
            "Algebra",
            *leaves(
                "Notation & simplifying",
                "Expanding & factorising",
                "Linear equations",
                "Rearranging formulae",
                "Sequences",
                "Coordinates & straight-line graphs",
                "Quadratics (graphs & solving)",
                "Simultaneous equations",
                "Inequalities",
                "Functions",
                "Graph transformations",
                "Iteration",
                "Algebraic fractions",
                "Proof",
            ).toTypedArray(),
        ),
        group(
            "Ratio, proportion & rates of change",
            *leaves(
                "Ratio problems",
                "Direct & inverse proportion",
                "Compound measures (speed, density, pressure)",
                "Growth & decay",
                "Gradients as rates",
            ).toTypedArray(),
        ),
        group(
            "Geometry & measures",
            *leaves(
                "Angles & parallel lines",
                "Polygons",
                "Triangles & congruence",
                "Similarity",
                "Transformations",
                "Constructions & loci",
                "Pythagoras",
                "Trigonometry (SOHCAHTOA)",
                "Exact trig values",
                "Sine & cosine rules",
                "Circle theorems",
                "Area & perimeter",
                "Volume & surface area",
                "Vectors",
            ).toTypedArray(),
        ),
        group(
            "Probability",
            *leaves(
                "Basic probability",
                "Relative frequency & expected outcomes",
                "Sample space & two-way tables",
                "Tree diagrams",
                "Venn diagrams & set notation",
                "Conditional probability",
            ).toTypedArray(),
        ),
        group(
            "Statistics",
            *leaves(
                "Sampling",
                "Averages & range",
                "Averages from frequency tables",
                "Cumulative frequency & box plots",
                "Histograms",
                "Scatter graphs & correlation",
                "Time series",
                "Misleading graphs",
            ).toTypedArray(),
        ),
    ),
)
