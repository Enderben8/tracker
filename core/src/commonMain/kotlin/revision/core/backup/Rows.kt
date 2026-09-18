package revision.core.backup

import revision.core.db.RevisionDatabase
import revision.core.db.Segment
import revision.core.db.Session
import revision.core.db.Session_topic
import revision.core.db.Subject
import revision.core.db.Topic
import revision.core.db.Topic_state

// Conversions between database rows and the JSON row types shared by backup and sync.

fun Subject.toRow() = SubjectRow(id, name, colour, exam_board, exam_date, sort_order, updated_at, deleted)
fun Topic.toRow() = TopicRow(id, subject_id, parent_id, code, title, page_start, sort_order, notes, updated_at, deleted)
fun Topic_state.toRow() = TopicStateRow(topic_id, last_revised_at, due_at, interval_days, ease_factor, repetitions, confidence, updated_at, deleted)
fun Session.toRow() = SessionRow(id, subject_id, started_at, ended_at, notes, is_manual, updated_at, deleted)
fun Session_topic.toRow() = SessionTopicRow(id, session_id, topic_id, rating, notes, updated_at, deleted)
fun Segment.toRow() = SegmentRow(id, session_topic_id, started_at, ended_at, updated_at, deleted)

fun SubjectRow.write(db: RevisionDatabase) =
    db.subjectQueries.insert(id, name, colour, examBoard, examDate, sortOrder, updatedAt, deleted)

fun TopicRow.write(db: RevisionDatabase) =
    db.topicQueries.insert(id, subjectId, parentId, code, title, pageStart, sortOrder, notes, updatedAt, deleted)

fun TopicStateRow.write(db: RevisionDatabase) =
    db.topicStateQueries.upsert(topicId, lastRevisedAt, dueAt, intervalDays, easeFactor, repetitions, confidence, updatedAt, deleted)

fun SessionRow.write(db: RevisionDatabase) =
    db.sessionQueries.insertSession(id, subjectId, startedAt, endedAt, notes, isManual, updatedAt, deleted)

fun SessionTopicRow.write(db: RevisionDatabase) =
    db.sessionQueries.insertSessionTopic(id, sessionId, topicId, rating, notes, updatedAt, deleted)

fun SegmentRow.write(db: RevisionDatabase) =
    db.sessionQueries.insertSegment(id, sessionTopicId, startedAt, endedAt, updatedAt, deleted)

fun SettingRow.write(db: RevisionDatabase) = db.settingQueries.put(key, value, updatedAt)
