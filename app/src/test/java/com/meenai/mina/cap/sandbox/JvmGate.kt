package com.meenai.mina.cap.sandbox

import com.meenai.mina.core.workspace.NioFdSys
import com.meenai.mina.core.workspace.WorkspaceFileGate

/** JVM 测试里的关口(按路径的近似;手机上是 AndroidFdSys.gate)。 */
internal val JVM_GATE = WorkspaceFileGate(NioFdSys)
