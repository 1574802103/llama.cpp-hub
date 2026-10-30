/**
 * 模型图标匹配。
 *
 * 规则：品牌名优先，架构兜底。
 * GGUF 的 general.architecture 和实际品牌不总是一致，例如 GLM-4.7-Flash 的 arch 是
 * deepseek2，只按 arch 匹配会显示成 DeepSeek 的图标。所以先用模型的
 * 别名 / 名称 / 目录 ID 匹配品牌关键字，匹配不到再看 architecture。
 *
 * 兼容两种调用：
 *   getModelIcon(architecture)        —— 旧调用
 *   getModelIcon(name, architecture)  —— 新调用（推荐）
 */
function getModelIcon(arg1, arg2) {
    var name = '';
    var arch = '';
    if (arg2 === undefined || arg2 === null) {
        arch = arg1 || '';
    } else {
        name = arg1 || '';
        arch = arg2 || '';
    }

    var nameKey = String(name).toLowerCase().replace(/[^a-z0-9]/g, '');
    var archKey = String(arch).toLowerCase().replace(/[^a-z0-9]/g, '');

    // 品牌关键字（按名称匹配，顺序敏感：先命中先返回）
    var nameMap = [
        ['chatglm', 'icon/glm.png'],
        ['glm', 'icon/glm.png'],
        ['qwen', 'icon/qwen.png'],
        ['qwq', 'icon/qwen.png'],
        ['qwythos', 'icon/qwen.png'],
        ['gemma', 'icon/gemma.png'],
        ['paddleocr', 'icon/paddleocr.png'],
        ['deepseek', 'icon/deepseek.png'],
        ['hunyuan', 'icon/hunyuan.png'],
        ['minimax', 'icon/minimax.png'],
        ['kimi', 'icon/kimi.png'],
        ['moonshot', 'icon/kimi.png'],
        ['bailing', 'icon/bailingmoe.png'],
        ['mistral', 'icon/mistral3.png'],
        ['mixtral', 'icon/mistral3.png'],
        ['magistral', 'icon/mistral3.png'],
        ['devstral', 'icon/mistral3.png'],
        ['nemotron', 'icon/nemotron.png'],
        ['llama', 'icon/llama.png'],
        ['seed', 'icon/seed_oss.png'],
        ['stepfun', 'icon/step35.png'],
        ['step', 'icon/step35.png'],
        ['mimo', 'icon/mimo.png'],
        ['lfm', 'icon/lfm.png'],
        ['liquid', 'icon/lfm.png'],
        ['bert', 'icon/bert.png'],
        ['gpt', 'icon/openai.png'],
        ['muse', 'icon/llama.png']
    ];

    for (var i = 0; i < nameMap.length; i++) {
        if (nameKey && nameKey.indexOf(nameMap[i][0]) !== -1) {
            return nameMap[i][1];
        }
    }

    // 架构兜底（同时匹配原始 arch 和去掉数字后的 arch，兼容 gemma4 / qwen35 / deepseek2）
    var archNoDigits = archKey.replace(/[0-9]/g, '');
    var archMap = [
        ['paddleocr', 'icon/paddleocr.png'],
        ['bailingmoe', 'icon/bailingmoe.png'],
        ['deepseek', 'icon/deepseek.png'],
        ['hunyuan', 'icon/hunyuan.png'],
        ['minimax', 'icon/minimax.png'],
        ['nemotron', 'icon/nemotron.png'],
        ['mistral', 'icon/mistral3.png'],
        ['moonshot', 'icon/kimi.png'],
        ['kimi', 'icon/kimi.png'],
        ['qwen', 'icon/qwen.png'],
        ['glm', 'icon/glm.png'],
        ['gemma', 'icon/gemma.png'],
        ['llama', 'icon/llama.png'],
        ['seedoss', 'icon/seed_oss.png'],
        ['seed', 'icon/seed_oss.png'],
        ['step', 'icon/step35.png'],
        ['mimo', 'icon/mimo.png'],
        ['lfm', 'icon/lfm.png'],
        ['bert', 'icon/bert.png'],
        ['gpt', 'icon/openai.png'],
        ['muse', 'icon/llama.png'],
        ['hy', 'icon/hunyuan.png']
    ];

    for (var j = 0; j < archMap.length; j++) {
        var key = archMap[j][0];
        if (archKey && archKey.indexOf(key) !== -1) return archMap[j][1];
        if (archNoDigits && archNoDigits.indexOf(key) !== -1) return archMap[j][1];
    }

    return null;
}
