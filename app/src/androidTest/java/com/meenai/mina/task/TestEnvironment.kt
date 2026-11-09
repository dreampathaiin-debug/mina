package com.meenai.mina.task

import androidx.test.platform.app.InstrumentationRegistry

/**For real machine testing:The task environment in the App process under test(Change the model source, evaluation hook, and view the screen). */
internal val testTasks: TaskEnvironment
    get() = TaskEnvironment.of(InstrumentationRegistry.getInstrumentation().targetContext)
