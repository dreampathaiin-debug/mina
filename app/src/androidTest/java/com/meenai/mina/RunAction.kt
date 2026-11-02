package com.meenai.mina

import com.meenai.mina.core.action.ActionExecutor
import com.meenai.mina.core.action.ActionRecorder
import com.meenai.mina.core.action.Origin
import com.meenai.mina.core.action.execution
import com.meenai.mina.core.guard.OperationGuards
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.record.RunSink
import com.meenai.mina.core.tool.ToolDispatcher
import com.meenai.mina.core.tool.ToolExecution

/** 设备测试里直接做一个工具调用:和产品一样经执行入口(不配确认关卡,不等锁屏)。 */
suspend fun ToolDispatcher.runAction(call: Part.ToolCall): ToolExecution =
    ActionExecutor(this, OperationGuards(), null, gate = {}, recorder = ActionRecorder(RunSink.NONE)).run(call, Origin.Model(0)).execution()
