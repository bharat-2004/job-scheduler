package com.jobscheduler.common.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/** Spring Data repository fragment, picked up by its "Impl" name suffix. */
public class InsertSupportImpl implements InsertSupport {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public <E> E insert(E entity) {
        entityManager.persist(entity);
        return entity;
    }
}
