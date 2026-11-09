"""Map validated, ID-bound target module responses to the core API."""
import base64
import re

from .completion import submit_completion


def convert_object(obj):
    return {'objectType': obj['object_type'], 'attributes': obj['attributes'], 'description': obj.get('description'),
            'bboxXyxy': obj.get('bbox_xyxy'), 'uncertainFields': obj.get('uncertain_fields', [])}


async def analyze_targets(core, gateway, image, worker, job, image_transport='json'):
    plan = job['targetPlan']
    metrics=plan.get('selection_metrics') or {}
    route=metrics.get('activity_target_routing') or {}
    activity=metrics.get('activity')
    if route.get('route')=='local_no_moving_person_vehicle':
        if (plan.get('detection_state')!='complete' or plan.get('targets') or not isinstance(activity,dict)
                or activity.get('policy')!='motion-v1' or activity.get('regions_complete') is not True
                or plan.get('detector') not in {'nanodet','yolox'}
                or not re.fullmatch(r'[0-9a-f]{64}',plan.get('model_sha256',''))):
            raise ValueError('local motion rejection is not supported by a complete target plan')
        # A real Core transaction records the local engine in module results;
        # this is not a fabricated VLM response or a successful model call.
        await submit_completion(core,'/internal/v2/analysis/complete',{
            'jobId':job['jobId'],'workerId':worker,'generation':job['generation'],
            'modelId':'local-'+plan['detector']+'@'+plan['model_sha256'],'promptVersion':'local-moving-pv-filter-v1',
            'targets':[],'additionalObjects':[],'sceneDescription':None},0,job)
        return True
    metadata = {
        'frame_id': job['frameId'], 'media_type': 'image/jpeg',
        'image_width': plan['image_width'], 'image_height': plan['image_height'],
        'targets': [{k: v[k] for k in ('target_id', 'object_type', 'class_name', 'bbox_xyxy')} for v in plan['targets']]}
    if activity is not None:
        metadata['activity_context']=activity
    if image_transport=='multipart':
        response=await gateway.post_image('/internal/v3/analyze-targets',metadata,image)
    else:
        response=await gateway.post('/internal/v2/analyze-targets',{**metadata,'image_base64':base64.b64encode(image).decode()})
    if response.get('frame_id') != job['frameId']:
        raise ValueError('analysis frame binding differs')
    result = response['result']
    if activity is not None:
        if response.get('activity_scope_applied') is not True or response.get('prompt_version') not in {'bound-targets-activity-v2','bound-targets-activity-v3','bound-targets-activity-v4','bound-targets-activity-zh-v5'}:
            raise ValueError('gateway has not applied the requested activity scope')
        from services.scene_analysis.activity_regions import active_target
        additional=[]
        for obj in result['additional_objects']:
            box=obj.get('bbox_xyxy')
            if box is None:raise ValueError('activity addition requires an exact image box')
            normalized=[box[0]/plan['image_width'],box[1]/plan['image_height'],box[2]/plan['image_width'],box[3]/plan['image_height']]
            if active_target(normalized,activity):additional.append(obj)
        result={**result,'additional_objects':additional}
    targets = [{'targetId': v['target_id'], 'objectType': v['object_type'], 'visible': v['visible'],
                'attributes': v['attributes'], 'description': v.get('description'), 'uncertainFields': v.get('uncertain_fields', [])}
               for v in result['targets']]
    count=sum(v['visible'] is True for v in result['targets'])+len(result['additional_objects'])
    await submit_completion(core,'/internal/v2/analysis/complete', {'jobId': job['jobId'], 'workerId': worker,
                    'generation': job['generation'], 'modelId': response['configured_model'], 'promptVersion': response['prompt_version'],
                    'targets': targets, 'additionalObjects': [convert_object(v) for v in result['additional_objects']],
                    'sceneDescription': result.get('scene_description'),**({'inferenceReceipt':response['inference_receipt']} if response.get('inference_receipt') else {})},count,job)
    return True
