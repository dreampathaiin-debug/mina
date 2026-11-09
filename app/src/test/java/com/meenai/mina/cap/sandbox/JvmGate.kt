package com.meenai.mina.cap.sandbox

import com.meenai.mina.core.workspace.NioFdSys
import com.meenai.mina.core.workspace.WorkspaceFileGate

/**Gates in JVM testing(Approximation by path;On the mobile phone it is AndroidFdSys.gate). */
internal val JVM_GATE = WorkspaceFileGate(NioFdSys)
