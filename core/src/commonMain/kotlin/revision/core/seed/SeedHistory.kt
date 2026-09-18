package revision.core.seed

// Source: gcse_revision_guides_contents.md section 5 (HIGH confidence).
// The book covers 11 chapters but this student takes only four (confirmed with the user):
//   1A Ch.2  Germany, 1890-1945
//   1B Ch.4  Conflict and tension, 1894-1918
//   2A Ch.8  Health and the people, c1000-present
//   2B Ch.11 Elizabethan England, c1568-1603
// "Exam focus" is kept as a topic; "Glossary" is dropped.

val historySeed = SeedSubject(
    key = "history", name = "History", colour = "#8D6E63", examBoard = "AQA",
    topics = listOf(
        group(
            "Germany, 1890–1945: Democracy and dictatorship",
            leaf("Kaiser Wilhelm and the difficulties of ruling Germany, 1890–1914", "2.1", 30),
            leaf("The impact of the First World War on Germany", "2.2", 32),
            leaf("The new Weimar government: initial problems and recovery under Stresemann", "2.3", 34),
            leaf("The impact of the Depression on Germany", "2.4", 36),
            leaf("The failure of Weimar democracy: Hitler becomes Chancellor, Jan 1933", "2.5", 38),
            leaf("The establishment of Hitler's dictatorship, 1933–34", "2.6", 40),
            leaf("Economic changes: employment and rearmament", "2.7", 42),
            leaf("The impact of Nazi social policies", "2.8", 44),
            leaf("The Nazi dictatorship", "2.9", 46),
            leaf("Exam focus", page = 48),
            code = "2", page = 30,
        ),
        group(
            "Conflict and tension, 1894–1918",
            leaf("The alliance system", "4.1", 76),
            leaf("Anglo-German rivalry", "4.2", 78),
            leaf("The outbreak of war", "4.3", 80),
            leaf("Tactics and technology on the Western Front", "4.4", 82),
            leaf("Key battles on the Western Front", "4.5", 84),
            leaf("The war on other fronts", "4.6", 86),
            leaf("Changes in 1917", "4.7", 88),
            leaf("The war in 1918", "4.8", 90),
            leaf("German surrender", "4.9", 92),
            leaf("Exam focus", page = 94),
            code = "4", page = 76,
        ),
        group(
            "Health and the people: c1000 to the present day",
            leaf("Key features of British medicine in the Middle Ages", "8.1A", 166),
            leaf("Main influences on British medicine in the Middle Ages", "8.1B", 166),
            leaf("Public health in the Middle Ages", "8.2", 168),
            leaf("Impact of the Renaissance on medicine in Britain", "8.3", 170),
            leaf("Dealing with disease", "8.4", 172),
            leaf("Germ Theory and its impact", "8.5A", 174),
            leaf("A revolution in surgery", "8.5B", 174),
            leaf("Improvements in public health", "8.6", 176),
            leaf("Modern treatment of disease and surgical advancements", "8.7", 178),
            leaf("Modern public health", "8.8", 180),
            leaf("Exam focus", "8.9", 182),
            code = "8", page = 166,
        ),
        group(
            "Elizabethan England, c1568–1603",
            leaf("Elizabeth's character and Court life", "11.1", 234),
            leaf("Elizabeth and Parliament", "11.2", 236),
            leaf("The Elizabethan 'Golden Age'", "11.3", 238),
            leaf("Poverty: attitudes and responses", "11.4", 240),
            leaf("English sailors: Hawkins, Drake and Raleigh", "11.5", 242),
            leaf("Religion: plots, threats and government responses", "11.6", 244),
            leaf("Mary, Queen of Scots: threat, plots, execution and impact", "11.7", 246),
            leaf("Conflict with Spain and the defeat of the Spanish Armada", "11.8", 248),
            leaf("Exam focus", page = 250),
            leaf("Spelling, Punctuation and Grammar", page = 253),
            code = "11", page = 234,
        ),
    ),
)
