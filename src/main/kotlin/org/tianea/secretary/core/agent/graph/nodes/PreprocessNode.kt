package org.tianea.secretary.core.agent.graph.nodes

import ai.koog.agents.core.dsl.builder.AIAgentNodeDelegate
import ai.koog.agents.core.dsl.builder.node

/**
 * `preprocess` 노드 정의.
 *
 * 현재는 입력을 trim만 한다. 향후 의도 분류·정규화 같은 입력 가공 로직을 끼워 넣을 자리.
 */
fun preprocessNodeDelegate(): AIAgentNodeDelegate<String, String> =
    node("preprocess") { input -> input.trim() }
