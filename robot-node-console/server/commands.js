'use strict';

/**
 * 指令定义（前端按钮的唯一数据来源）。
 *
 * code / 参数格式参考《机器人二次开发API接口文档-应用层》，
 * 全部通过 POST {robotBaseUrl}/robot/api/common 下发，请求体 { code, authKey, reqJson }。
 *
 * async = true 表示「异步返回」：HTTP 200 只代表已受理，最终结果由机器狗回调
 * POST /api/robot/callBackMess 回传（messType 一般为下发时的 code）。
 */

const COMMANDS = [
  {
    code: 104,
    key: 'charge',
    name: '回充',
    async: true,
    description: '一键返航（回桩充电），异步返回',
    params: []
  },
  {
    code: 108,
    key: 'standUp',
    name: '起立',
    async: false,
    description: '机器狗起立',
    params: []
  },
  {
    code: 109,
    key: 'lieDown',
    name: '趴下',
    async: false,
    description: '机器狗趴下',
    params: []
  },
  {
    code: 116,
    key: 'switchMode',
    name: '模式切换',
    async: false,
    description: '切换运控模式：1 SALM / 2 手柄',
    params: [
      {
        name: 'mode',
        label: '模式',
        type: 'select',
        valueType: 'number',
        default: 1,
        options: [
          { value: 1, label: '1 - SALM' },
          { value: 2, label: '2 - 手柄' }
        ]
      }
    ]
  },
  {
    code: 401,
    key: 'status',
    name: '机器狗运行状态',
    async: false,
    description: '查询运行状态：电量 dl、状态 sta、坐标 xyz 等',
    resultView: 'status',
    params: []
  },
  {
    code: 404,
    key: 'setDogHouse',
    name: '设置狗窝',
    async: false,
    description: '设置狗窝位置与安全位置（坐标为 x,y,z，其中 z 为朝向）',
    params: [
      { name: 'id', label: '狗窝 ID', type: 'number', valueType: 'number', default: 1 },
      {
        name: 'xyz',
        label: '狗窝位置 x,y,z(朝向)',
        type: 'text',
        placeholder: '1.037931,-0.309902,1.030920'
      },
      {
        name: 'safe_xyz',
        label: '安全位置 x,y,z(朝向)',
        type: 'text',
        placeholder: '留空则与狗窝位置相同'
      }
    ]
  },
  {
    code: 402,
    key: 'navigate',
    name: '一键导航',
    async: true,
    description: '导航到指定驻足点，异步返回（到位后回调）',
    params: [
      { name: 'presetCode', label: '摄像机预置位 presetCode', type: 'text', placeholder: '10' },
      {
        name: 'xyz',
        label: '驻足点 x,y,z(朝向)',
        type: 'text',
        placeholder: '1.037931,-0.309902,1.030920'
      }
    ]
  },
  {
    code: 309,
    key: 'startMapping',
    name: '开始建图',
    async: true,
    description: '开始建图（开始采集点云数据），异步返回',
    params: []
  },
  {
    code: 319,
    key: 'stopMapping',
    name: '关闭建图',
    async: false,
    description: '关闭建图（关闭采集点云数据）',
    params: []
  }
];

/** 异步回调 messType 与下发 code 的别名映射（一键导航 402 的回调可能是 320） */
const CALLBACK_CODE_ALIAS = {
  320: 402
};

function list() {
  return COMMANDS.map((item) => JSON.parse(JSON.stringify(item)));
}

function findByCode(code) {
  const value = Number(code);
  return COMMANDS.find((item) => item.code === value) || null;
}

/** 把回调的 messType 还原为下发时的 code */
function resolveCallbackCode(messType) {
  const value = Number(messType);
  if (CALLBACK_CODE_ALIAS[value] !== undefined) {
    return CALLBACK_CODE_ALIAS[value];
  }
  return value;
}

module.exports = {
  COMMANDS,
  CALLBACK_CODE_ALIAS,
  list,
  findByCode,
  resolveCallbackCode
};
