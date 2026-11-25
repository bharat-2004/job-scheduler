package com.jobscheduler.worker.runtime;

import com.jobscheduler.common.domain.Language;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Looks up the runtime for a language among all LanguageRuntime beans. */
@Component
@Profile("worker")
public class LanguageRuntimes {

    private final Map<Language, LanguageRuntime> byLanguage = new EnumMap<>(Language.class);

    public LanguageRuntimes(List<LanguageRuntime> runtimes) {
        runtimes.forEach(r -> byLanguage.put(r.language(), r));
    }

    public LanguageRuntime forLanguage(Language language) {
        LanguageRuntime runtime = byLanguage.get(language);
        if (runtime == null) {
            throw new IllegalStateException("no runtime registered for " + language);
        }
        return runtime;
    }

    public Collection<LanguageRuntime> all() {
        return byLanguage.values();
    }
}
