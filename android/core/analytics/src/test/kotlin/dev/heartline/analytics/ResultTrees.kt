package dev.heartline.analytics

internal fun SessionResult.tree(): Map<String, Any?> = mapOf(
    "file" to file, "start" to start, "duration_s" to durationS, "signal_quality" to signalQuality,
    "usable_s" to usableS, "hr_bpm" to hrBpm, "beats" to beats,
    "hrv" to mapOf("mean_rr_ms" to hrv.meanRrMs, "sdnn_ms" to hrv.sdnnMs, "rmssd_ms" to hrv.rmssdMs,
        "pnn50" to hrv.pnn50, "sd1_ms" to hrv.sd1Ms, "sd2_ms" to hrv.sd2Ms, "lf" to hrv.lf, "hf" to hrv.hf,
        "lf_hf" to hrv.lfHf, "stress_index" to hrv.stressIndex),
    "rhythm" to mapOf("status" to rhythm.status, "ectopic_beats" to rhythm.ectopicBeats,
        "irregularity" to rhythm.irregularity, "notes" to rhythm.notes, "features" to rhythm.features?.let {
            mapOf("nrmssd" to it.nrmssd, "shannon_entropy" to it.shannonEntropy, "tpr" to it.tpr,
                "random_like" to it.randomLike, "intervals" to it.intervals) }),
    "respiratory_rate" to respiratoryRate,
    "respiration" to mapOf("riiv" to respiration.riiv, "riav" to respiration.riav, "rifv" to respiration.rifv),
    "rr" to rr.map { mapOf("t" to it.t, "rr_ms" to it.rrMs, "ectopic" to it.ectopic) },
    "ppg_preview" to ppgPreview, "preview_fs" to previewFs, "beat_times" to beatTimes, "beat_good" to beatGood,
    "motion" to motion.map { mapOf("t" to it.t, "g" to it.g) },
)

internal fun DailyResult.tree(): Map<String, Any?> = mapOf(
    "days" to days.map { d -> buildMap {
        putAll(mapOf("date" to d.date, "resting_hr" to d.restingHr, "hrv_rmssd" to d.hrvRmssd,
            "sleep_minutes" to d.sleepMinutes, "sleep_efficiency" to d.sleepEfficiency, "deep_min" to d.deepMin,
            "light_min" to d.lightMin, "rem_min" to d.remMin, "awake_min" to d.awakeMin, "sleep_score" to d.sleepScore,
            "steps" to d.steps, "active_minutes" to d.activeMinutes, "worn_minutes" to d.wornMinutes,
            "strain" to d.strain, "trimp" to d.trimp,
            "hr_zones" to d.hrZones?.let { mapOf("z1" to it.z1, "z2" to it.z2, "z3" to it.z3, "z4" to it.z4, "z5" to it.z5) },
            "spo2_avg" to d.spo2Avg, "stress_avg" to d.stressAvg, "readiness" to d.readiness, "calories_active" to d.caloriesActive))
        if (d.sleepStart != null) put("sleep_start", d.sleepStart)
        if (d.sleepEnd != null) put("sleep_end", d.sleepEnd)
    } },
    "today" to mapOf("readiness" to today.readiness, "strain" to today.strain, "sleep_score" to today.sleepScore,
        "body_battery" to today.bodyBattery, "recommendation" to today.recommendation, "note" to today.note,
        "last_readiness" to today.lastReadiness?.let { mapOf("date" to it.date, "value" to it.value) },
        "readiness_factors" to today.readinessFactors.map { mapOf("name" to it.name, "impact" to it.impact, "detail" to it.detail) }),
    "trends" to mapOf("resting_hr_7d" to trends.restingHr7d, "resting_hr_28d" to trends.restingHr28d,
        "hrv_7d" to trends.hrv7d, "hrv_28d" to trends.hrv28d, "vo2max_estimate" to trends.vo2maxEstimate,
        "fitness_age" to trends.fitnessAge, "sleep_regularity" to trends.sleepRegularity,
        "cardio_load_7d" to trends.cardioLoad7d, "acute_chronic_ratio" to trends.acuteChronicRatio,
        "hr_max_used" to trends.hrMaxUsed, "hr_rest_used" to trends.hrRestUsed),
    "alerts" to alerts.map { mapOf("level" to it.level, "title" to it.title, "detail" to it.detail) },
)
