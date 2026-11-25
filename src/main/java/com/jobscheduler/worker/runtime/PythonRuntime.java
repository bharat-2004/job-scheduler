package com.jobscheduler.worker.runtime;

import com.jobscheduler.common.domain.Language;
import com.jobscheduler.worker.WorkerProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("worker")
public class PythonRuntime implements LanguageRuntime {

    private final String image;

    public PythonRuntime(WorkerProperties props) {
        this.image = props.sandbox().pythonImage();
    }

    @Override
    public Language language() {
        return Language.PYTHON;
    }

    @Override
    public String image() {
        return image;
    }

    @Override
    public String scriptFileName() {
        return "script.py";
    }

    @Override
    public List<String> command(String scriptPath) {
        // -u: unbuffered, so output reaches the log even if the run is killed at its timeout.
        return List.of("python", "-u", scriptPath);
    }
}
