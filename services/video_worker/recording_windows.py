"""Keyframe windows shared by a time group, independent of selected-frame rate."""
from dataclasses import dataclass
import uuid

LEGACY_SCHEMA='same-session-recording-v1'
CONTEXT_SCHEMA='same-session-recording-v2'
LEGACY_SEGMENT_BYTES=16*1024*1024
SEGMENT_BYTES=128*1024*1024
MANIFEST_BYTES=1024*1024
MAX_DURATION_US=75_000_000
MAX_PACKETS=7200


@dataclass(frozen=True)
class PacketWindow:
    identity:str
    key_pts:int
    base_pts_us:int
    start_dts_us:int


class RecordingWindows:
    def __init__(self,context_seconds,segment_seconds):
        if type(context_seconds) is not int or context_seconds not in (0,3,5,10):raise ValueError('invalid recording context')
        if type(segment_seconds) is not int or not 2<=segment_seconds<=10:raise ValueError('invalid recording segment')
        self.context_us=context_seconds*1_000_000
        self.step_us=2*self.context_us if self.context_us else segment_seconds*1_000_000
        self.capacity=2 if self.context_us else 1
        self.windows=[]

    def clear(self):self.windows.clear()

    def advance(self,keyframe,key_pts,pts_us,dts_us):
        if keyframe and (not self.windows or dts_us-self.windows[-1].start_dts_us>=self.step_us):
            self.windows.append(PacketWindow(str(uuid.uuid4()),key_pts,pts_us,dts_us))
            self.windows=self.windows[-self.capacity:]
        return tuple(self.windows)

    def binding_window(self,pts_us):
        eligible=[window for window in self.windows if pts_us-window.base_pts_us>=self.context_us]
        return eligible[-1] if eligible else self.windows[0] if self.windows else None
