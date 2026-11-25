package com.jobscheduler.common.repository;

/**
 * Our entities get their IDs in Java (factory methods), so Spring Data's save() cannot tell
 * they are new and would merge() them: a SELECT before every INSERT. insert() persists directly.
 * Existing entities are updated by dirty checking inside the transaction, never by save().
 */
public interface InsertSupport {

    <E> E insert(E entity);
}
