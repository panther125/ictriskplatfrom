package com.riskstrategy.engine.script;

/** 画布编译 / 脚本解析错误：消息带节点与行号上下文，可直接展示给使用者。 */
public class ScriptException extends RuntimeException {

    public ScriptException(String message) {
        super(message);
    }
}
