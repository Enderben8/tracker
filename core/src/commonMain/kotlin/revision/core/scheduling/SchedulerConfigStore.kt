package revision.core.scheduling

import revision.core.data.SettingsRepository

/** Saves the user-tunable scheduler numbers in the `setting` table. Unset keys keep the defaults. */
class SchedulerConfigStore(private val settings: SettingsRepository) {

    fun load(): SchedulerConfig {
        val d = SchedulerConfig()
        fun num(key: String, default: Double) = settings.get("sched.$key")?.toDoubleOrNull() ?: default
        return d.copy(
            overdueWeight = num("overdueWeight", d.overdueWeight),
            neverRevisedWeight = num("neverRevisedWeight", d.neverRevisedWeight),
            stalenessWeight = num("stalenessWeight", d.stalenessWeight),
            examPressureWeight = num("examPressureWeight", d.examPressureWeight),
            justRevisedPenalty = num("justRevisedPenalty", d.justRevisedPenalty),
            queueSize = num("queueSize", d.queueSize.toDouble()).toInt().coerceIn(1, 50),
            maxPerSubject = num("maxPerSubject", d.maxPerSubject.toDouble()).toInt().coerceIn(1, 50),
        )
    }

    fun save(c: SchedulerConfig) {
        settings.put("sched.overdueWeight", c.overdueWeight.toString())
        settings.put("sched.neverRevisedWeight", c.neverRevisedWeight.toString())
        settings.put("sched.stalenessWeight", c.stalenessWeight.toString())
        settings.put("sched.examPressureWeight", c.examPressureWeight.toString())
        settings.put("sched.justRevisedPenalty", c.justRevisedPenalty.toString())
        settings.put("sched.queueSize", c.queueSize.toString())
        settings.put("sched.maxPerSubject", c.maxPerSubject.toString())
    }

    fun resetToDefaults() = save(SchedulerConfig())
}
