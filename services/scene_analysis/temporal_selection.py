"""Bounded look-back selection; protected candidates cannot be coalesced away."""
from dataclasses import dataclass
import math

from .motion_guard import guard_activity,guard_uncertain


@dataclass
class TemporalCandidate:
    offset_ms:int
    state:str
    reason:str
    quality:dict
    payload:object
    payload_bytes:int
    peak:bool=False
    available_ms:int|None=None
    burst_id:int|None=None


class TemporalSelector:
    def __init__(self,*,merge=True,width=320,max_entries=12,max_bytes=64*1024*1024):
        if type(merge) is not bool or width not in {320,640} or not 4<=max_entries<=32 or not 8*1024*1024<=max_bytes<=128*1024*1024:
            raise ValueError('invalid temporal buffer policy')
        self.merge=merge;self.width=width;self.max_entries=max_entries;self.max_bytes=max_bytes
        self.pending={};self.burst={};self.burst_start=None;self.last_activity=None;self.last_burst=None
        self.last_time=None;self.last_output=None;self.merged=0;self.peak_windows=0;self.pressure_flushes=0
        self.peak_entries=0;self.peak_bytes=0;self.closed=False
        self.recent_peaks=[]

    def _mergeable(self,item):
        return item.reason=='active_interval' and item.state=='active' and not item.peak and item.quality['usable_for_empty_confirmation']

    def _usage(self):
        unique={**self.pending,**self.burst}
        return len(unique),sum(v.payload_bytes for v in unique.values())

    def _finish_burst(self,now):
        if not self.burst:return
        best=max(self.burst.values(),key=lambda v:v.quality['motion_rank'])
        best.peak=True;best.available_ms=now;best.burst_id=self.burst_start
        if not best.reason:best.reason='small_motion_peak'
        self.pending[best.offset_ms]=best;self.peak_windows+=1
        if self.merge and best.state=='active' and best.quality['usable_for_empty_confirmation']:
            self.recent_peaks=[p for p in self.recent_peaks if now-p<=2000]+[best.offset_ms]
            for key,item in list(self.pending.items()):
                if key!=best.offset_ms and abs(key-best.offset_ms)<=1000 and self._mergeable(item):
                    del self.pending[key];self.merged+=1
        self.burst={};self.burst_start=None

    def _drain(self,watermark):
        result=[]
        for key in sorted(k for k in self.pending if k<=watermark):
            value=self.pending.pop(key)
            if self.last_output is not None and key<=self.last_output:raise ValueError('temporal output time regressed')
            self.last_output=key;result.append(value)
        return result

    def offer(self,offset_ms,state,reason,q,guard,payload,payload_bytes,*,warming_up=False,shake=False):
        if self.closed or type(offset_ms) is not int or offset_ms<0 or self.last_time is not None and offset_ms<=self.last_time:
            raise ValueError('nonmonotonic temporal input')
        if state not in {'active','unknown','occupied_quiet','empty_candidate'} or not 0<payload_bytes<=self.max_bytes:
            raise ValueError('invalid temporal frame')
        self.last_time=offset_ms;emitted=[]
        entries,used=self._usage()
        if entries>=self.max_entries or used+payload_bytes>self.max_bytes:
            self._finish_burst(offset_ms);emitted.extend(self._drain(math.inf));self.pressure_flushes+=1
        item=TemporalCandidate(offset_ms,state,reason or '',{**q,'motion_rank':guard['largest_component']+guard['edge_component']},payload,payload_bytes)
        if reason:
            if self.merge and self._mergeable(item) and any(0<=offset_ms-p<=1000 for p in self.recent_peaks):self.merged+=1
            else:self.pending[offset_ms]=item
        if self.burst_start is not None:
            self.burst[offset_ms]=item
            if offset_ms-self.burst_start>=600:self._finish_burst(offset_ms)
        safe=guard_activity(guard,self.width) and q['usable_for_empty_confirmation'] and not guard_uncertain(guard) and not warming_up and not shake
        if safe:
            if (self.burst_start is None and (self.last_activity is None or offset_ms-self.last_activity>=600)
                    and (self.last_burst is None or offset_ms-self.last_burst>=2000)):
                self.burst_start=offset_ms;self.last_burst=offset_ms;self.burst={offset_ms:item}
            self.last_activity=offset_ms
        entries,used=self._usage();self.peak_entries=max(self.peak_entries,entries);self.peak_bytes=max(self.peak_bytes,used)
        emitted.extend(self._drain(offset_ms-1000))
        return emitted

    def finish(self):
        if self.closed:return []
        self.closed=True;self._finish_burst(self.last_time or 0)
        return self._drain(math.inf)

    def snapshot(self):
        entries,used=self._usage()
        return {'temporal_merged_candidates':self.merged,'temporal_peak_windows':self.peak_windows,
                'temporal_buffer_peak_entries':self.peak_entries,'temporal_buffer_peak_bytes':self.peak_bytes,
                'temporal_pressure_flushes':self.pressure_flushes,'temporal_pending_entries':entries,'temporal_pending_bytes':used,
                'temporal_merge_radius_ms':1000,'temporal_buffer_delay_ms':1000}
