/** Business views depend on this lifecycle contract; provider SDK objects stay in adapters. */
const providers=new Map()
export function registerMapProvider(id,factory){if(providers.has(id))throw new Error('地图适配器重复登记。');providers.set(id,factory)}
export async function createMapProvider(id,element,configuration,callbacks){const factory=providers.get(id);if(!factory)throw new Error('当前地图提供商尚未安装。');return factory(element,configuration,callbacks)}
