package com.aurora.app.core.display;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * 屏幕断点兜底的<b>第二条通道</b>：持久化 JobScheduler 任务。
 *
 * <h3>为什么需要它</h3>
 * 第一条通道是 {@code BOOT_COMPLETED} 广播（{@code BootCompletedReceiver}）。
 * 但在小米 / HyperOS 上这条路会被 ROM 直接掐断，真机日志：
 * <pre>
 * W BroadcastQueueInjector: Unable to launch app com.aurora.app/10436
 *   for broadcast Intent { act=android.intent.action.BOOT_COMPLETED }:
 *   process is not permitted to  auto start
 * </pre>
 * 未加入「自启动」白名单时，系统根本不会为广播拉起本应用进程 →
 * 未确认的屏幕改动在重启后不会被还原。
 *
 * <p>{@code setPersisted(true)} 的任务由系统 JobScheduler 持有，<b>跨重启保留</b>，
 * 开机后由系统侧重新入队并绑定本服务。它与广播队列是两条不同的启动路径，
 * ROM 未必一并拦掉 —— 这是本类存在的唯一理由。若在目标 ROM 上同样被拦，
 * 就只剩「下次打开应用时由 {@code AuroraApplication} 兜底」这一条路了。
 * <b>注意</b>：本通道在真机上是否真能跑通尚未验证，需要重启实测确认。
 *
 * <h3>时序设计</h3>
 * 任务用 {@link #LATENCY_MS}（30 秒）的延迟派发，而不是立刻跑：
 * 页面上改完会先走 15 秒倒计时，用户在倒计时里点「保留」就会调
 * {@link #cancel(Context)} 撤销本任务。若不延迟，任务会在倒计时结束前抢跑，
 * 把用户正想保留的改动直接回滚掉。
 *
 * <p>30 秒这个值比 15 秒倒计时大得多，同时又足够短，不会让用户在重启后
 * 长时间停在错误的分辨率上。
 */
public class DisplayRestoreJobService extends JobService {

    private static final String TAG = "DisplayRestoreJob";

    /** 任务 id：常量唯一即可，固定值保证重复 schedule 是「替换」而不是「新增」。 */
    private static final int JOB_ID = 0x41555231; // "AUR1"

    /** 首次派发延迟：必须大于 {@link DisplayGuard#COUNTDOWN_MS}，避免抢跑回滚。 */
    private static final long LATENCY_MS = 30_000L;

    /** 兜底截止：即使系统一直觉得「不划算」也必须在 2 分钟内跑一次。 */
    private static final long DEADLINE_MS = 120_000L;

    /**
     * 登记一个「待确认改动」的兜底任务。已有同 id 任务时被替换。
     *
     * <p>在 {@link DisplayGuard#markPending} 成功后调用。
     */
    public static void schedule(Context context) {
        try {
            JobScheduler scheduler =
                    (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null) {
                return;
            }
            JobInfo job = new JobInfo.Builder(JOB_ID,
                    new ComponentName(context, DisplayRestoreJobService.class))
                    .setPersisted(true)
                    .setMinimumLatency(LATENCY_MS)
                    .setOverrideDeadline(DEADLINE_MS)
                    .build();
            scheduler.schedule(job);
            Log.i(TAG, "已登记屏幕还原兜底任务");
        } catch (Exception e) {
            // 任务派发失败不能影响用户的当前操作，记录后放行
            Log.w(TAG, "登记屏幕还原兜底任务失败", e);
        }
    }

    /** 用户确认或已回滚后撤销兜底任务。 */
    public static void cancel(Context context) {
        try {
            JobScheduler scheduler =
                    (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler != null) {
                scheduler.cancel(JOB_ID);
            }
        } catch (Exception e) {
            Log.w(TAG, "撤销屏幕还原兜底任务失败", e);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final Context appContext = getApplicationContext();
        if (!DisplayGuard.isPending(appContext)) {
            // 用户已经确认过，或者倒计时已经回滚过 —— 无事可做
            Log.i(TAG, "兜底任务执行：无待确认改动");
            return false;
        }
        // root 命令要几百毫秒，不能占住 JobService 的主线程
        new Thread(() -> {
            boolean restored = false;
            try {
                restored = DisplayGuard.restoreIfPending(appContext);
            } catch (Exception e) {
                Log.w(TAG, "兜底任务还原失败", e);
            } finally {
                Log.i(TAG, "兜底任务执行: " + (restored ? "已还原" : "无需还原"));
                jobFinished(params, false);
            }
        }, "aurora-job-display-restore").start();
        return true; // 有异步工作在跑，稍后自己调 jobFinished
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // 被系统中断时要求重排：宁可晚一点还原，也不能把未确认的改动留在设备上
        return true;
    }
}
