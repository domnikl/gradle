/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.tooling.internal.provider.runner;

import org.gradle.tooling.internal.provider.events.AbstractTaskResult;
import org.gradle.tooling.internal.provider.events.OperationResultPostProcessor;

import java.util.List;

public class CompositeOperationResultPostProcessor implements OperationResultPostProcessor {

    private final List<OperationResultPostProcessor> processors;

    CompositeOperationResultPostProcessor(List<OperationResultPostProcessor> processors) {
        this.processors = processors;
    }

    @Override
    public AbstractTaskResult process(AbstractTaskResult taskResult, Object taskBuildOperationId) {
        for (OperationResultPostProcessor factory : processors) {
            taskResult = factory.process(taskResult, taskBuildOperationId);
        }
        return taskResult;
    }

}
