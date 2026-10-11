#include "app_obd_dsp/perf_monitor_core.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#ifdef _WIN32
#include <windows.h>
#endif

static void feed(perf_stats_t *s, uint16_t k, uint16_t a, uint32_t t, uint32_t v, uint32_t loss)
{
    perf_event_t e = {.kind=k, .arg=a, .time_us=t, .value=v, .loss=loss};
    perf_stats_consume(s, &e);
}
static void queue_limits(void)
{
    perf_queue_t q = {0};
    perf_event_t e = {0}, out;
    for (unsigned round = 0; round < 3; ++round) {
        for (unsigned i=0; i<PERF_QUEUE_CAPACITY; ++i) {
            e.value=i;
            assert(perf_queue_push(&q,e));
        }
        assert(!perf_queue_push(&q,e));
        for (unsigned i=0; i<PERF_QUEUE_CAPACITY; ++i) {
            assert(perf_queue_pop(&q,&out));
            assert(out.value==i);
        }
        assert(!perf_queue_pop(&q,&out));
    }
    assert(q.lost==3);
    q.gate=1; /* A preempted consumer must never make producers wait. */
    assert(!perf_queue_push(&q,e));
    assert(!perf_queue_pop(&q,&out));
    assert(q.lost==4);
    q.gate=0;
    q.head=q.tail=UINT32_MAX-2;
    for (unsigned i=0; i<8; ++i) assert(perf_queue_push(&q,e));
    for (unsigned i=0; i<8; ++i) assert(perf_queue_pop(&q,&out));
    assert(!perf_queue_pop(&q,&out));
    assert(out.loss==4);
}
static void timing(void)
{
    perf_stats_t s={0};
    assert(perf_command_code((const uint8_t *)"010C\r",5)==0x010c);
    assert(perf_command_code((const uint8_t *)"ATZ\r",4)==0xffff);
    assert(perf_command_code(NULL,5)==0xffff);
    feed(&s,PERF_REQUEST,0x010c,UINT32_MAX-999,0,0);
    feed(&s,PERF_RX,0,1000,0,0);
    feed(&s,PERF_RX,0,1500,0,0);
    perf_stats_new_window(&s); /* Reply crosses reporting boundary. */
    feed(&s,PERF_PROMPT,0,3000,0,0);
    assert(s.rtt[0].n==1 && s.rtt[0].max==4000);
    assert(s.first_rx[0].n==1 && s.first_rx[0].max==2000);
    feed(&s,PERF_REQUEST,0x010d,10000,0,0);
    feed(&s,PERF_CANCEL,PERF_TIMEOUT,11000,0,0);
    feed(&s,PERF_REQUEST,0x010d,11500,0,0);
    feed(&s,PERF_PROMPT,0,12000,0,0);
    assert(s.rtt[1].n==0 && s.cancels[PERF_TIMEOUT]==1);
    feed(&s,PERF_REQUEST,0x010c,20000,0,0);
    feed(&s,PERF_PROMPT,0,30000,0,1); /* Lost request or cancellation: never pair. */
    assert(s.rtt[0].n==1 && !s.pending);
    feed(&s,PERF_REQUEST,0x010d,31000,0,1);
    feed(&s,PERF_RX,0,32000,0,1);
    feed(&s,PERF_PROMPT,0,33000,0,1);
    assert(s.rtt[1].n==1 && s.rtt[1].max==2000);
    feed(&s,PERF_RPM,0,40000,7,1);
    feed(&s,PERF_RPM,0,140000,9,1);
    assert(s.samples[0]==2 && s.interval[0].n==1 && s.interval[0].max==100000);
    assert(s.work[0].total==16 && s.work[0].max==9);
    perf_stats_new_window(&s);
    feed(&s,PERF_RPM,0,240000,8,1);
    assert(s.samples[0]==1 && s.interval[0].max==100000);
    feed(&s,PERF_CANCEL,PERF_DISCONNECT,250000,0,1);
    feed(&s,PERF_RPM,0,1000000,8,1);
    assert(s.interval[0].n==1); /* Reconnect does not pollute jitter. */
    feed(&s,PERF_REFRESH,1000,1010000,16000,1);
    assert(s.refresh_us.max==16000 && s.pixels.total==1000);
    for (unsigned i=0; i<1000; ++i) feed(&s,PERF_PARSE,0,0,10000000,1);
    assert(s.work[2].total==10000000000ULL); /* No 32-bit sum overflow. */
}
#ifdef _WIN32
static perf_queue_t concurrent;
static volatile LONG done;
static uint32_t accepted[5];
static DWORD WINAPI producer(LPVOID param)
{
    uint16_t id=(uint16_t)(uintptr_t)param;
    for (uint32_t i=0; i<20000; ++i) {
        perf_event_t e={.kind=PERF_UI,.arg=id,.time_us=i,.value=i ^ id};
        if (perf_queue_push(&concurrent,e)) ++accepted[id];
    }
    InterlockedIncrement(&done);
    return 0;
}
static void concurrency(void)
{
    HANDLE handles[4];
    for (unsigned i=0; i<4; ++i) {
        handles[i]=CreateThread(NULL,0,producer,(LPVOID)(uintptr_t)(i+1),0,NULL);
        assert(handles[i]);
    }
    uint32_t consumed=0, last[5]={0};
    bool seen[5]={0};
    perf_event_t e;
    for (;;) {
        if (perf_queue_pop(&concurrent,&e)) {
            assert(e.arg>=1 && e.arg<=4 && e.value==(e.time_us ^ e.arg));
            if (seen[e.arg]) assert(e.time_us>last[e.arg]);
            seen[e.arg]=true; last[e.arg]=e.time_us;
            ++consumed;
        } else if (InterlockedCompareExchange(&done,0,0)==4) break;
        else SwitchToThread();
    }
    while (perf_queue_pop(&concurrent,&e)) ++consumed;
    WaitForMultipleObjects(4,handles,TRUE,INFINITE);
    assert(consumed==accepted[1]+accepted[2]+accepted[3]+accepted[4]);
    assert(consumed+concurrent.lost<=80000); /* simultaneous loss markers may coalesce */
    if (consumed<80000) assert(concurrent.lost>0);
    for (unsigned i=0; i<4; ++i) CloseHandle(handles[i]);
}
#endif
int main(void)
{
    queue_limits(); timing();
#ifdef _WIN32
    concurrency();
#endif
    puts("PASS: bounded queue, contention, overflow, timestamp wrap, loss, cancellation, window continuity");
    return 0;
}
