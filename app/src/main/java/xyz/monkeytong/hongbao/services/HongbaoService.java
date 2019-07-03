package xyz.monkeytong.hongbao.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.graphics.Path;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.DisplayMetrics;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;

import xyz.monkeytong.hongbao.R;
import xyz.monkeytong.hongbao.utils.HongbaoSignature;
import xyz.monkeytong.hongbao.utils.PowerUtil;

import android.app.NotificationManager;
import android.content.Context;
import android.support.v4.app.NotificationCompat;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class HongbaoService extends AccessibilityService implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "HongbaoService";
    private static final String WECHAT_DETAILS_EN = "Details";
    private static final String WECHAT_DETAILS_CH = "红包详情";
    private static final String WECHAT_BETTER_LUCK_EN = "Better luck next time!";
    private static final String WECHAT_BETTER_LUCK_CH = "手慢了";
    private static final String WECHAT_EXPIRES_CH = "已超过24小时";
    private static final String WECHAT_VIEW_SELF_CH = "查看红包";
    private static final String WECHAT_VIEW_OTHERS_CH = "领取红包";
    private static final String WECHAT_NOTIFICATION_TIP = "[微信红包]";
    private static final String Alipay_NOTIFICATION_TIP = "已成功向你转了1笔钱";
    private static final String AlipayPackageName = "com.eg.android.AlipayGphone";
    private static final String CeoPackageName = "com.shiyebaidu.ceo";
    private static final String AlipayTransfer = "转账";
    private static final String AlipayTransferToYou = "转账给你";
    private static final String WECHAT_LUCKMONEY_RECEIVE_ACTIVITY = ".plugin.luckymoney.ui";//com.tencent.mm/.plugin.luckymoney.ui.En_fba4b94f  com.tencent.mm/com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyReceiveUI
    private static final String WECHAT_LUCKMONEY_DETAIL_ACTIVITY = "LuckyMoneyDetailUI";
    private static final String WECHAT_LUCKMONEY_GENERAL_ACTIVITY = "LauncherUI";
    private static final String WECHAT_LUCKMONEY_CHATTING_ACTIVITY = "ChattingUI";
    private static HongbaoService singleton = null;
    private String currentActivityName = WECHAT_LUCKMONEY_GENERAL_ACTIVITY;

    private AccessibilityNodeInfo rootNodeInfo, mReceiveNode, mUnpackNode;
    private boolean mLuckyMoneyPicked, mLuckyMoneyReceived;
    private int mUnpackCount = 0;
    private boolean mMutex = false, mListMutex = false, mChatMutex = false;
    private HongbaoSignature signature = new HongbaoSignature();

    private List<String> bills = new java.util.ArrayList<String>();
    private List<String> ceoToConfirmList = new ArrayList<String>();
    private PowerUtil powerUtil;
    private static SharedPreferences sharedPreferences;
    private int nid = 1;
    private long firstTimeInBillList = 0;
    private int billListRefreshed = 0;
    private int manualStart = 0;
    private int billInfoGot = 0;
    private int shouldInBillInfo = 0;
    private int firstTimeInMineView = 0;
    private PendingIntent contentIntent;
    private String notificationText = null;
    private int backedFromBusiness = 0;
    private int backedFromChat = 0;
    private int nameButtonClicked = 0;
    private int shouldInSenderAccount = 0;

    private Timer timer;
    private String trueName = null;
    private JSONObject payInfo = null;
    private java.util.concurrent.ConcurrentLinkedQueue<String> notifications = new java.util.concurrent.ConcurrentLinkedQueue<String>();
    private boolean isProcessingEvents = false;
    private int firstTimeInOtcBusiness0 = 0;
    private int firstTimeInOtcOrderDetail = 0;
    private int otcMenuBusinessClicked = 0;
    private int otcMenuClicked = 0;
    private int firstTimeInOtcBusiness1 = 0;
    private int firstTimeInOtcBusiness2 = 0;
    private int firstTimeInOtcBusiness3 = 0;
    private int firstTimeInOtcBusiness4 = 0;
    private static HashMap<String, String> otcToConfirmIds = new HashMap<String, String>();
    private String payPassword = "995561";
    private Runnable onEventProcessed;

    public static void toConfirm(String ids) {
        Log.i(TAG, "ids:" + ids);
        String[] idsa = ids.split(",");
        otcToConfirmIds.clear();
        for (int i = 0; i < idsa.length; ++i) {
            otcToConfirmIds.put(idsa[i], "");
        }

        if (singleton != null && otcToConfirmIds.size() > 0) {
            singleton.notifications.add("ceo:to confirm");
            singleton.processEvents();
        }
    }

    /**
     * AccessibilityEvent
     *
     * @param event 事件
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (sharedPreferences == null) return;
        setCurrentActivityName(event);

        /* 检测通知消息 */
        if (!mMutex) {
            if (sharedPreferences.getBoolean("pref_watch_notification", false) && watchNotifications(event))
                return;
            //if (sharedPreferences.getBoolean("pref_watch_list", false) && watchList(event)) return;
            mListMutex = false;
        }

        if (!mChatMutex) {
            mChatMutex = true;
            if (sharedPreferences.getBoolean("pref_watch_chat", true)) watchChat(event);
            mChatMutex = false;
        }
    }


    private void watchChatV1(AccessibilityEvent event) {
        if (HongbaoService.this.notificationText == null) return; //not open through notification;
        HongbaoService.this.rootNodeInfo = getRootInActiveWindow();

        if (rootNodeInfo == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        String cName = event.getClassName().toString();

        info("className", "className:" + cName);
        if (!cName.equals("com.alipay.mobile.chatapp.ui.PersonalChatMsgActivity_"))
            return;
        mReceiveNode = null;
        mUnpackNode = null;

        checkNodeInfo(event.getEventType());

        /* 如果已经接收到红包并且还没有戳开 */
        Log.d(TAG, "watchChat mLuckyMoneyReceived:" + mLuckyMoneyReceived + " mLuckyMoneyPicked:" + mLuckyMoneyPicked + " mReceiveNode:" + mReceiveNode);
        if (mLuckyMoneyReceived && (mReceiveNode != null)) {
            mMutex = true;

            mReceiveNode.getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
            mLuckyMoneyReceived = false;
            mLuckyMoneyPicked = true;
        }
        /* 如果戳开但还未领取 */
        Log.d(TAG, "戳开红包！" + " mUnpackCount: " + mUnpackCount + " mUnpackNode: " + mUnpackNode);
        if (mUnpackCount >= 1 && (mUnpackNode != null)) {
            int delayFlag = sharedPreferences.getInt("pref_open_delay", 0) * 1000;
            new android.os.Handler().postDelayed(
                    new Runnable() {
                        public void run() {
                            try {
                                openPacket();
                            } catch (Exception e) {
                                mMutex = false;
                                mLuckyMoneyPicked = false;
                                mUnpackCount = 0;
                            }
                        }
                    },
                    delayFlag);
        }
    }

    public boolean findNodesById(List<AccessibilityNodeInfo> nodes, AccessibilityNodeInfo root, String id) {
        if (root == null) {
            return false;
        }

        if (nodes.size() == 100) {
            HongbaoService.this.rootNodeInfo = getRootInActiveWindow();
            if (HongbaoService.this.rootNodeInfo == null) {
                return false;
            }

            List<AccessibilityNodeInfo> nodes1 = HongbaoService.this.rootNodeInfo.findAccessibilityNodeInfosByViewId(id);
            if (nodes1 != null && nodes1.size() > 0) {
                nodes.addAll(nodes1);
                return true;
            }
        }


        for (int i = 0; i < root.getChildCount(); ++i) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child == null) {
                continue;
            }

            String rid = child.getViewIdResourceName();
            if (rid != null && rid.equals(id)) {
                nodes.add(child);
            } else {
                HongbaoService.this.findNodesById(nodes, child, id);
            }
        }

        if (nodes.size() > 0) {
            return true;
        }

        return false;
    }

    private boolean isInChat(List<AccessibilityNodeInfo> nodes) {
        nodes.clear();
        AccessibilityNodeInfo node = HongbaoService.this.getTheLastNode("com.alipay.mobile.chatapp:id/biz_desc");
        if (node != null) {
            nodes.add(node);
            return true;
        }

        return false;
    }

    public static void updatePreference(String key, String value) {
        if (sharedPreferences == null) {
            return;
        }
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(key, value);
        editor.commit();
    }

    private boolean isInBill(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo lastNode = null, node;
        nodes.clear();
        if (HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.alipay.mobile.nebula:id/h5_tv_title")) {
            if ("账单详情".equals(nodes.get(0).getText())) {
                nodes.clear();
                HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.alipay.mobile.nebula:id/h5_pc_container");
                return true;
            }
        }

        return false;
    }

    private boolean isInBillList(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo lastNode = null, node;
        nodes.clear();
        if (HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.alipay.mobile.bill.list:id/listItem")) {

            return true;
        }


        return false;
    }

    private boolean isInBusiness(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo lastNode = null, node;
        nodes.clear();
        if (HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.alipay.mobile.nebula:id/h5_tv_title")) {

            if ("商家服务".equals(nodes.get(0).getText())) {
                return true;
            }
        }
        return false;
    }

    private boolean billExist(String bill) {
        for (int i = 0; i < HongbaoService.this.bills.size(); ++i) {
            if (bills.get(i).equals(bill)) {
                return true;
            }
        }

        return false;
    }

    private boolean clickBill(AccessibilityNodeInfo node) {
        List<AccessibilityNodeInfo> items = new java.util.ArrayList<AccessibilityNodeInfo>();
        Date now = new Date();
        String bill = "";
        if (HongbaoService.this.findNodesById(items, node, "com.alipay.mobile.bill.list:id/billName")) {
            bill = bill + items.get(0).getText().toString();
        }
        items.clear();
        if (HongbaoService.this.findNodesById(items, node, "com.alipay.mobile.bill.list:id/billAmount")) {
            bill = bill + items.get(0).getText().toString();
        }
        items.clear();
        if (HongbaoService.this.findNodesById(items, node, "com.alipay.mobile.bill.list:id/timeInfo1")) {
            String day = items.get(0).getText().toString();

            if ("今天".equals(day)) {
                day = now.getYear() + "-" + (now.getMonth() + 1) + "-" + now.getDate();
            } else if ("昨天".equals(day)) {
                Date yesterday = new Date(now.getTime() - 24 * 3600 * 1000);
                day = yesterday.getYear() + "-" + (yesterday.getMonth() + 1) + "-" + yesterday.getDate();
            }

            bill = bill + day;
        }
        items.clear();
        if (HongbaoService.this.findNodesById(items, node, "com.alipay.mobile.bill.list:id/timeInfo2")) {
            bill = bill + items.get(0).getText().toString();
        }
        items.clear();


        info(TAG, "bill:" + bill);
        if (billExist(bill)) {
            info(TAG, "already clicked");
            return false;
        } else {
            HongbaoService.this.bills.add(bill);
            HongbaoService.this.billInfoGot = 0;
            info(TAG, "clicking");
            HongbaoService.this.shouldInBillInfo = 1;
            nameButtonClicked = 0;
            trueName = null;
            node.getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
            return true;
        }
    }

    private boolean isInMineView(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo lastNode = null, node;
        nodes.clear();

        if (HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.alipay.mobile.antui:id/item_left_text")) {
            if (nodes.size() > 2 && containsText(nodes, "账单") > -1) {
                return true;
            }
        }

        return false;
    }

    private boolean isInCeoOtc(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo lastNode = null, node;
        nodes.clear();

        if (HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.shiyebaidu.ceo:id/tab_text")) {
            if (nodes.size() > 3 && nodes.get(3).getText().equals("法币")) {
                nodes.clear();

                HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.shiyebaidu.ceo:id/iv_otc_user_hub");
                if (nodes.size() > 0) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isInCeoOtcSellDetail(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo lastNode = null, node;
        nodes.clear();

        if (HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.shiyebaidu.ceo:id/tv_title_content")) {
            if (nodes.size() > 0 && nodes.get(0) != null && nodes.get(0).getText() != null && nodes.get(0).getText().toString().equals("订单详情（卖）")) {
                return true;
            }
        }

        return false;
    }

    private boolean isInCeoOtcBuyDetail(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo lastNode = null, node;
        nodes.clear();

        if (HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.shiyebaidu.ceo:id/tv_title_content")) {
            if (nodes.size() > 0 && nodes.get(0) != null && nodes.get(0).getText() != null && nodes.get(0).getText().toString().equals("订单详情（买）")) {
                return true;
            }
        }

        return false;
    }

    private int isInCeoOtcBusiness(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo lastNode = null;
        nodes.clear();
        int sel = -1;
        if (HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.shiyebaidu.ceo:id/tv_title_content")) {
            if (nodes.size() > 0 && nodes.get(0) != null && nodes.get(0).getText() != null && nodes.get(0).getText().equals("商户订单")) {
                nodes.clear();

                HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.shiyebaidu.ceo:id/tabLayout");

                if (nodes.size() > 0) {
                    AccessibilityNodeInfo root = nodes.get(0);
                    nodes.clear();
                    for (int i = 0; i < 5; ++i) {
                        AccessibilityNodeInfo node = getChild(root, "0" + i + "0");
                        if (node == null) {
                            return -1;
                        }
                        if (node.isSelected()) {
                            sel = i;
                        }
                        nodes.add(node);

                    }
                }
            }
        }

        return sel;
    }

    private boolean isInCeoOtcMenu(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo lastNode = null, node;
        nodes.clear();

        HongbaoService.this.rootNodeInfo = getRootInActiveWindow();

        if (HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.shiyebaidu.ceo:id/tab_text")) {
            if (nodes.size() > 3 && nodes.get(3).getText().equals("法币")) {

                nodes.clear();

                HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.shiyebaidu.ceo:id/fl_merchant_order");
                if (nodes.size() > 0) {
                    return true;
                }
            }
        }

        return false;
    }


    private boolean isInCeoNotOtc(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo lastNode = null, node;
        nodes.clear();

        if (HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.shiyebaidu.ceo:id/tab_text")) {
            if (nodes.size() > 3 && nodes.get(3).getText().equals("法币")) {
                nodes.clear();

                HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.shiyebaidu.ceo:id/iv_otc_user_hub");
                if (nodes.size() > 0) {
                    return false;
                }

                return true;
            }
        }

        return false;
    }

    private int containsText(List<AccessibilityNodeInfo> nodes, String text) {
        for (int i = 0; i < nodes.size(); ++i) {
            if (text.equals(nodes.get(i).getText())) {
                return i;
            }
        }

        return -1;
    }

    private boolean isInFirstPage(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo lastNode = null, node;
        nodes.clear();

        if (HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.alipay.android.phone.openplatform:id/app_text")) {
            if (nodes.size() > 2 && nodes.get(1).getText().equals("转账")) {
                return true;
            }
        }

        return false;
    }

    private boolean isInTransferFinishPage(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo lastNode = null, node;
        nodes.clear();

        if (HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.alipay.android.app:id/nav_right_textview")) {
            if (nodes.size() > 0 && nodes.get(0).getText().equals("完成")) {
                return true;
            }
        }

        return false;
    }

    private boolean isInSenderAccount(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo lastNode = null, node;
        nodes.clear();

        if (HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.alipay.mobile.transferapp:id/tf_receiveNameTextView")) {
            if (nodes.size() > 0) {
                return true;
            }
        }

        return false;
    }

    private boolean isInTransferPage(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo lastNode = null, node1 = null, node2 = null;
        nodes.clear();

        if (HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.alipay.mobile.transferapp:id/to_account_view")) {
            if (nodes.size() > 0) {
                node1 = nodes.get(0);
            }
        }

        nodes.clear();

        if (HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.alipay.mobile.transferapp:id/to_card_view")) {
            if (nodes.size() > 0) {
                node2 = nodes.get(0);
            }
        }

        if (node1 != null && node2 != null) {
            nodes.clear();
            nodes.add(node1);
            nodes.add(node2);
            return true;
        }


        return false;
    }

    private boolean isInTransferToAccountPage(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo lastNode = null, node1 = null, node2 = null;
        nodes.clear();

        if (HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.alipay.mobile.transferapp:id/tf_toAccountNextBtn")) {
            if (nodes.size() > 0) {
                node1 = nodes.get(0);
            }
        }

        nodes.clear();

        if (HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.alipay.mobile.antui:id/input_edit")) {
            if (nodes.size() > 0) {
                node2 = nodes.get(0);
            }
        }

        if (node1 != null && node2 != null) {
            nodes.clear();
            nodes.add(node1);
            nodes.add(node2);
            return true;
        }


        return false;
    }

    private boolean isInInputAmountToAccount(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo lastNode = null, node1 = null, node2 = null;
        nodes.clear();

        if (HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.alipay.mobile.antui:id/amount_edit")) {
            if (nodes.size() > 0) {
                node1 = nodes.get(0);
            }
        }

        nodes.clear();

        if (HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.alipay.mobile.transferapp:id/tf_nextBtn")) {
            if (nodes.size() > 0) {
                node2 = nodes.get(0);
            }
        }

        if (node1 != null && node2 != null) {
            nodes.clear();
            nodes.add(node1);
            nodes.add(node2);
            return true;
        }


        return false;
    }

    private boolean isInInputToCard(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo lastNode = null, node1 = null, node2 = null;
        nodes.clear();

        if (HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.alipay.mobile.ui:id/content")) {

            if (HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.alipay.mobile.transferapp:id/btn_next")) {
                if (nodes.size() == 4) {
                    return true;
                }
            }
        }

        return false;
    }

    AccessibilityNodeInfo getChild(AccessibilityNodeInfo root, String index) {
        if (index.length() == 0) {
            return root;
        }

        if (root == null) {
            return null;
        }


        int i = -1;
        String ch = index.substring(0, 1);
        if (ch.toLowerCase().equals("a")) {
            i = 10;
        } else if (ch.toLowerCase().equals("b")) {
            i = 11;
        } else if (ch.toLowerCase().equals("c")) {
            i = 12;
        } else {
            i = Integer.parseInt(ch);
        }

        if (root.getChildCount() <= i) {
            return null;
        }

        if (index.length() == 1) {
            return root.getChild(i);
        }

        return HongbaoService.this.getChild(root.getChild(i), index.substring(1, index.length()));
    }

    private synchronized void scanBillList() {

        if (firstTimeInBillList == 0) {
            info(TAG, "to scan bill list");
            firstTimeInBillList = 1;
        } else {
            return;
        }

        try {
            List<AccessibilityNodeInfo> nodes = new java.util.ArrayList<AccessibilityNodeInfo>();
            if (isInBillList(nodes)) {
                for (int i = 0; i < nodes.size(); ++i) {
                    AccessibilityNodeInfo node = nodes.get(i);
                    info(TAG, "checking item " + i);
                    List<AccessibilityNodeInfo> items = new java.util.ArrayList<AccessibilityNodeInfo>();
                    if (findNodesById(items, node, "com.alipay.mobile.bill.list:id/categoryTextView")) {
                        String text = items.get(0).getText().toString();
                        if ("小买卖".equals(text)) {
                            if (clickBill(node)) {
                                return;
                            }
                        } else if ("其他".equals(text)) {
                            if (clickBill(node)) {
                                return;
                            }
                        }
                    }
                }

                if (manualStart == 0) {
                    back(1000, null);
                    back(1500, onEventProcessed);
                } else {
                    back(500, onEventProcessed);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private void getBillInfo() {

        List<AccessibilityNodeInfo> nodes = new java.util.ArrayList<AccessibilityNodeInfo>();
        info(TAG, "getting bill info");
        if (isInBill(nodes)) {
            AccessibilityNodeInfo root = nodes.get(0);
            AccessibilityNodeInfo node = getChild(root, "00000");
            AccessibilityNodeInfo button = node;
            if (node != null) {
                String clsName = node.getClassName().toString();
                String amount = "", name = "", mobile = "", reference = "", time = "";

                String tid = "";
                if (clsName.equals("android.widget.Button")) {
                    node = getChild(root, "00001");
                    if (node != null && node.getText() != null) {
                        amount = node.getText().toString().replaceAll(",", "");
                    } else {
                        if (node != null && node.getContentDescription() != null) {
                            amount = node.getContentDescription().toString().replaceAll(",", "");
                        }
                    }

                    node = getChild(root, "000080");
                    if (node != null && node.getText() != null) {
                        name = node.getText().toString();
                        if (name.contains(" ")) {
                            String[] s = name.split(" ");
                            name = s[0];
                            mobile = s[1];
                        }
                    } else {

                        node = getChild(root, "00008");
                        if (node != null && node.getContentDescription() != null) {
                            name = node.getContentDescription().toString();
                            if (name.contains(" ")) {
                                String[] s = name.split(" ");
                                name = s[0];
                                mobile = s[1];
                            }
                        }
                    }


                    node = getChild(root, "000060");
                    if (node != null && node.getText() != null) {
                        reference = node.getText().toString();
                    } else {
                        node = getChild(root, "00006");
                        if (node != null && node.getContentDescription() != null) {
                            reference = node.getContentDescription().toString();
                        }
                    }

                    node = getChild(root, "0000C0");
                    if (node != null && node.getText() != null) {
                        tid = node.getText().toString();
                    } else {
                        node = getChild(root, "0000C");
                        if (node != null && node.getContentDescription() != null) {
                            tid = node.getContentDescription().toString();
                        }
                    }

                    node = getChild(root, "0000A0");
                    if (node != null && node.getText() != null) {
                        time = node.getText().toString();
                    } else {
                        node = getChild(root, "0000A");
                        if (node != null && node.getContentDescription() != null) {
                            time = node.getContentDescription().toString();
                        }
                    }

                    if ("".equals(amount + reference + name + mobile)) {
                        Log.d(TAG, "bill info not ready");
                        return;
                    } else {
                        synchronized (HongbaoService.class) {
                            if (nameButtonClicked == 0) {
                                trueName = null;
                                shouldInSenderAccount = 1;
                                info(TAG, "clicking button");
                                button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                nameButtonClicked = 1;
                                return;
                            }

                            if (nameButtonClicked == 1 && trueName == null) {
                                return;
                            }
                        }
                    }
                } else if (clsName.equals("android.view.View")) {
                    node = getChild(root, "000001");
                    if (node != null && node.getText() != null) {
                        name = node.getText().toString();
                    } else {
                        node = getChild(root, "00001");
                        if (node != null && node.getContentDescription() != null) {
                            name = node.getContentDescription().toString();
                        }
                    }

                    node = getChild(root, "00001");
                    if (node != null && node.getText() != null) {
                        amount = node.getText().toString();
                    } else {
                        node = getChild(root, "00002");
                        if (node != null && node.getContentDescription() != null) {
                            amount = node.getContentDescription().toString();
                        }
                    }

                    node = getChild(root, "000040");
                    if (node != null && node.getText() != null) {
                        reference = node.getText().toString();
                        if (reference.contains("-")) {
                            reference = reference.split("-")[1];
                        }
                    } else {
                        node = getChild(root, "00005");
                        if (node != null && node.getContentDescription() != null) {
                            reference = node.getContentDescription().toString();
                            if (reference.contains("-")) {
                                reference = reference.split("-")[1];
                            }
                        }
                    }

                    node = getChild(root, "000060");
                    if (node != null && node.getText() != null) {
                        time = node.getText().toString();
                    } else {
                        node = getChild(root, "00007");
                        if (node != null && node.getContentDescription() != null) {
                            time = node.getContentDescription().toString();

                        }
                    }

                    node = getChild(root, "000080");
                    if (node != null && node.getText() != null) {
                        tid = node.getText().toString();
                    } else {
                        node = getChild(root, "00009");
                        if (node != null && node.getContentDescription() != null) {
                            tid = node.getContentDescription().toString();

                        }
                    }

                }

                if ("".equals(amount + reference + name + mobile)) {
                    info(TAG, "bill info not ready");
                    return;
                }

                amount = amount.replaceAll(",", "");
                HongbaoService.this.billInfoGot = 1;
                synchronized (this) {
                    if (notificationText != null) {
                        if (trueName != null) {
                            name = trueName;
                        }
                        sendIntent(new String[]{"app", "com.eg.android.AlipayGphone", "title", "支付宝收款", "id", tid, "amount", amount, "referenceId", reference, "createTime", time, "trueName", name});
                        firstTimeInBillList = 0;
                        back(500);
                    }
                }
            } else {
                info(TAG, "bill sender not ready");
            }

        } else {
            info(TAG, "not in bill");
        }

    }

    private void watchChat(AccessibilityEvent event) {
        if (HongbaoService.this.notificationText == null && HongbaoService.this.payInfo == null)
            return; //not open through notification;
        HongbaoService.this.rootNodeInfo = getRootInActiveWindow();

        if (rootNodeInfo == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.getEventType() != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && event.getEventType() != AccessibilityEvent.TYPE_WINDOWS_CHANGED
                ) return;
        String cName = event.getClassName().toString();

        Log.d(TAG, cName);
        Log.d(TAG, "" + event.getEventType());

        List<AccessibilityNodeInfo> nodes = new java.util.ArrayList<AccessibilityNodeInfo>();
        if (isInBillList(nodes)) {
            Log.d(TAG, "is in bill list");
            if (HongbaoService.this.payInfo != null) {
                back(500);
                return;
            }
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                info(TAG, "is in bill list");
                synchronized (HongbaoService.class) {
                    if (billListRefreshed == 0) {
                        info(TAG, "to refresh bill list");
                        billListRefreshed = 1;
                        sleep(1500, new Runnable() {
                            @Override
                            public void run() {
                                HongbaoService.this.refreshBillList();
                            }
                        });
                    } else {
                        scanBillList();
                    }
                }
            }
        } else if (isInChat(nodes)) {
            info(TAG, "is in chat");

            if (HongbaoService.this.payInfo != null) {
                back(500);
                return;
            }
            if (HongbaoService.this.backedFromChat == 0) {
                HongbaoService.this.backedFromChat = 1;
                back(1000);
            }
        } else if (isInBusiness(nodes)) {
            Log.d(TAG, "is in business");
            if (HongbaoService.this.payInfo != null) {
                back(500);
                return;
            }
            if (HongbaoService.this.backedFromBusiness == 0) {
                info(TAG, "back from business");
                HongbaoService.this.backedFromBusiness = 1;
                back(3000);
            }
        } else if (HongbaoService.this.payInfo == null && isInSenderAccount(nodes)) {
            info(TAG, "is in sender");


            if (HongbaoService.this.shouldInSenderAccount == 0) {
                info(TAG, "should not in sender");
                back(500);

                return;
            }
            if (trueName != null) {
                info(TAG, "already got trueName");
                return;
            }

            CharSequence text = nodes.get(0).getText();
            if (text == null) {
                text = nodes.get(0).getContentDescription();
            }


            String str = text.toString();
            int start = str.indexOf("（");
            int end = str.indexOf("）");
            trueName = str.substring(start + 1, end);
            info(TAG, "back from sender account");
            billInfoGot = 0;
            back(500);
            back(500);
        } else if (isInBill(nodes)) {
            info(TAG, "is in bill");
            if (HongbaoService.this.payInfo != null) {
                back(500);
                return;
            }

            if (HongbaoService.this.shouldInBillInfo == 1) {
                if (HongbaoService.this.billInfoGot == 0) {
                    info(TAG, "to get bill info");
                    HongbaoService.this.getBillInfo();
                    return;
                }
            } else {
                back(500);
            }

        } else if (isInTransferPage(nodes)) {
            info(TAG, "is in first page to click transfer app");
            if (HongbaoService.this.payInfo != null) {
                if (HongbaoService.this.payInfo.optString("type").equals("alipay")) {
                    nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                } else {
                    nodes.get(1).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
            } else {
                back(500);
            }
        } else if (isInTransferToAccountPage(nodes)) {
            info(TAG, "is in first page to click transfer app");
            if (HongbaoService.this.payInfo != null) {
                ClipboardManager clipboard = (ClipboardManager) HongbaoService.this.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("text", payInfo.optString("account", ""));
                clipboard.setPrimaryClip(clip);
                if (nodes.get(1).getText().toString().indexOf("手机号码") >= 0) {
                    nodes.get(1).performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    nodes.get(1).performAction(AccessibilityNodeInfo.ACTION_PASTE);
                    nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
            } else {
                back(500);
            }
        } else if (isInInputAmountToAccount(nodes)) {
            info(TAG, "is in first page to click transfer app");
            if (HongbaoService.this.payInfo != null) {
                final ClipboardManager clipboard = (ClipboardManager) HongbaoService.this.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("text", payInfo.optString("amount", "0.01"));
                clipboard.setPrimaryClip(clip);

                nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_PASTE);
                nodes.get(1).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                final List<AccessibilityNodeInfo> finalNodes1 = nodes;
                sleep(3000, new Runnable() {
                    @Override
                    public void run() {

                        HongbaoService.this.rootNodeInfo = getRootInActiveWindow();
                        finalNodes1.clear();
                        if (HongbaoService.this.findNodesById(finalNodes1, HongbaoService.this.rootNodeInfo, "com.alipay.mobile.antui:id/ensure")) {
                            AccessibilityNodeInfo node = getChild(finalNodes1.get(0).getParent().getParent(), "10");
                            if (node != null) {
                                ClipData clip = ClipData.newPlainText("text", payInfo.optString("name").substring(0, 1));
                                clipboard.setPrimaryClip(clip);

                                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                                node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                                finalNodes1.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            }
                        }

                        payInfo = null;
                    }
                });


            } else {
                back(500);
            }
        } else if (isInInputToCard(nodes)) {
            info(TAG, "is in card info page");
            if (HongbaoService.this.payInfo != null) {
                final List<AccessibilityNodeInfo> finalNodes = nodes;
                sleep(2000, new Runnable() {
                    @Override
                    public void run() {

                        if (finalNodes.get(0).getText().toString().indexOf("收款人姓名") >= 0) {
                            info(TAG, "start to paste");
                            ClipboardManager clipboard = (ClipboardManager) HongbaoService.this.getSystemService(Context.CLIPBOARD_SERVICE);
                            int i = 0;

                            ClipData clip = ClipData.newPlainText("text", payInfo.optString("name", ""));
                            clipboard.setPrimaryClip(clip);
                            finalNodes.get(i).performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                            tsleep(500);
                            finalNodes.get(i++).performAction(AccessibilityNodeInfo.ACTION_PASTE);
                            tsleep(500);

                            clip = ClipData.newPlainText("text", payInfo.optString("card", ""));
                            clipboard.setPrimaryClip(clip);
                            finalNodes.get(i).performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                            tsleep(500);
                            finalNodes.get(i++).performAction(AccessibilityNodeInfo.ACTION_PASTE);
                            tsleep(500);

                            clip = ClipData.newPlainText("text", payInfo.optString("amount", ""));
                            clipboard.setPrimaryClip(clip);
                            finalNodes.get(i).performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                            tsleep(500);
                            finalNodes.get(i++).performAction(AccessibilityNodeInfo.ACTION_PASTE);
                            payInfo = null;
                        }
                    }
                });
            } else {
                back(500);
            }
        } else if (HongbaoService.this.isInTransferFinishPage(nodes)) {
            info(TAG, "is in transfer finish page");
            back(500);
        } else if (HongbaoService.this.isInCeoOtcMenu(nodes)) {
            info(TAG, "is in ceo otc menu");
            if (otcMenuBusinessClicked == 0) {
                otcMenuBusinessClicked = 1;
                sleep(1000, new Runnable() {
                    @Override
                    public void run() {
                        List<AccessibilityNodeInfo> nodes1 = new ArrayList<AccessibilityNodeInfo>();
                        if (HongbaoService.this.isInCeoOtcMenu(nodes1)) {
                            nodes1.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        }
                    }
                });
            }
        } else if (HongbaoService.this.isInCeoOtcSellDetail(nodes)) {
            info(TAG, "is in otc sell detail");
            if (firstTimeInOtcOrderDetail == 0) {
                firstTimeInOtcOrderDetail = 1;
                info(TAG, "get order info");
                sleep(2000, new Runnable() {
                    @Override
                    public void run() {

                        String[] info = HongbaoService.this.getSellDetailInfo();
                        if (info == null) {
                            info(TAG, "no sell detail info found");
                            firstTimeInOtcBusiness0 = 0;
                            back(500);
                        } else {
                            info[13] = "买" + info[13];

                            String tid = info[5];

                            if ("买已完成".equals(info[13])) {
                                otcToConfirmIds.remove(tid);
                            }

                            if (otcToConfirmIds.containsKey(tid)) {
                                final List<AccessibilityNodeInfo> nodes1 = new ArrayList<AccessibilityNodeInfo>();
                                HongbaoService.this.findNodesById(nodes1, HongbaoService.this.rootNodeInfo, "com.shiyebaidu.ceo:id/btn_sell_commit");
                                if (nodes1.size() >= 1) {
                                    nodes1.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                }

                                sleep(1000, new Runnable() {
                                    @Override
                                    public void run() {
                                        ClipboardManager clipboard = (ClipboardManager) HongbaoService.this.getSystemService(Context.CLIPBOARD_SERVICE);
                                        ClipData clip = ClipData.newPlainText("text", payPassword);
                                        clipboard.setPrimaryClip(clip);

                                        HongbaoService.this.findNodesById(nodes1, HongbaoService.this.rootNodeInfo, "com.shiyebaidu.ceo:id/cet_pay_password");

                                        if (nodes1.size() >= 1) {
                                            nodes1.get(0).performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                                            nodes1.get(0).performAction(AccessibilityNodeInfo.ACTION_PASTE);
                                            nodes1.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                        }

                                        tsleep(500);

                                        HongbaoService.this.findNodesById(nodes1, HongbaoService.this.rootNodeInfo, "com.shiyebaidu.ceo:id/checkbox");
                                        if (nodes1.size() >= 1) {
                                            nodes1.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                        }

                                        tsleep(500);
                                        /*
                                        HongbaoService.this.findNodesById(nodes1, HongbaoService.this.rootNodeInfo, "com.shiyebaidu.ceo:id/btn_commit");
                                        if (nodes1.size() >= 1) {
                                            nodes1.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                        }

                                        back(500);
                                        */
                                    }
                                });

                            } else {
                                sendIntent(info);
                                firstTimeInOtcBusiness0 = 0;
                                firstTimeInOtcBusiness1 = 0;
                                firstTimeInOtcBusiness2 = 0;
                                firstTimeInOtcBusiness3 = 0;
                                firstTimeInOtcBusiness4 = 0;
                                back(500);
                            }
                        }
                    }
                });
            }
        } else if (HongbaoService.this.isInCeoOtcBuyDetail(nodes)) {
            info(TAG, "is in otc buy detail");
            if (firstTimeInOtcOrderDetail == 0) {
                firstTimeInOtcOrderDetail = 1;
                info(TAG, "get buy info");
                sleep(1500, new Runnable() {
                    @Override
                    public void run() {
                        String[] info = HongbaoService.this.getSellDetailInfo();
                        if (info == null) {
                            info(TAG, "no buy detail info found");
                            firstTimeInOtcBusiness0 = 0;
                            back(500);
                        } else {
                            info[13] = "卖" + info[13];
                            sendIntent(info);
                            firstTimeInOtcBusiness0 = 0;
                            back(500);
                        }
                    }
                });
            }
        } else if (HongbaoService.this.isInCeoOtcBusiness(nodes) > -1) {
            info(TAG, "is in ceo otc business");
            int sel = HongbaoService.this.isInCeoOtcBusiness(nodes);

            if (!nodes.get(3).getText().toString().equals("待确认")) {
                info(TAG, "not have 待确认");
                return;
            }

            if (sel == 0) {
                if (firstTimeInOtcBusiness0 == 0) {
                    firstTimeInOtcBusiness0 = 1;
                    info(TAG, "0: clicking 3");
                    nodes.get(3).getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
            } else if (sel == 1) {
                if (firstTimeInOtcBusiness1 == 0) {
                    firstTimeInOtcBusiness1 = 1;
                    info(TAG, "1: clicking 3");
                    nodes.get(3).getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
            } else if (sel == 2) {
                if (firstTimeInOtcBusiness2 == 0) {
                    firstTimeInOtcBusiness2 = 1;
                    info(TAG, "2: clicking 3");
                    nodes.get(3).getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
            } else if (sel == 3) {
                if (firstTimeInOtcBusiness3 == 0) {
                    firstTimeInOtcBusiness3 = 1;
                    info(TAG, "to scan ceo otc toconfirm list");
                    sleep(1000, new Runnable() {
                        @Override
                        public void run() {
                            HongbaoService.this.scanCeoToConfirmList();
                        }
                    });
                }
            } else {
                if (firstTimeInOtcBusiness4 == 0) {
                    firstTimeInOtcBusiness4 = 1;
                    info(TAG, "4: clicking 3");
                    nodes.get(3).getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
            }
        } else if (HongbaoService.this.isInCeoOtc(nodes)) {
            info(TAG, "is in ceo otc");
            if (otcMenuClicked == 0) {
                otcMenuClicked = 1;
                sleep(500, new Runnable() {
                    @Override
                    public void run() {
                        List<AccessibilityNodeInfo> nodes1 = new ArrayList<AccessibilityNodeInfo>();
                        if (HongbaoService.this.isInCeoOtc(nodes1)) {
                            nodes1.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        }
                    }
                });
            }
        } else if (HongbaoService.this.isInCeoNotOtc(nodes)) {
            info(TAG, "is in ceo not otc");
            sleep(500, new Runnable() {
                @Override
                public void run() {
                    List<AccessibilityNodeInfo> nodes1 = new ArrayList<AccessibilityNodeInfo>();
                    if (HongbaoService.this.isInCeoNotOtc(nodes1)) {
                        nodes1.get(3).getParent().getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                }
            });
        } else {
            if (HongbaoService.this.payInfo != null) {
                if (isInFirstPage(nodes)) {
                    info(TAG, "is in first page to click transfer app");

                    nodes.get(1).getParent().getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);

                } else if (HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.alipay.android.phone.openplatform:id/tab_description")) {
                    info(TAG, "is in tab page, to click first page");
                    if (nodes.size() > 0) {
                        nodes.get(0).getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                }

                return;
            }

            if (isInMineView(nodes)) {
                info(TAG, "is in mine view");
                nodes = HongbaoService.this.rootNodeInfo.findAccessibilityNodeInfosByText("账单");
                if (nodes != null && nodes.size() > 0 && firstTimeInMineView == 0) {
                    firstTimeInMineView = 1;
                    firstTimeInBillList = 0;
                    billListRefreshed = 0;
                    nodes.get(0).getParent().getParent().getParent().getParent().getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
            } else if (HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.alipay.android.phone.wealth.home:id/sigle_tab_bg")) {
                info(TAG, "is in tab page, to click my page");
                if (nodes.size() > 0) {
                    firstTimeInMineView = 0;
                    nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
            }
        }
    }

    private void tsleep(long i) {
        try {
            Thread.sleep(i);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

    private String getTextById(String id) {
        HongbaoService.this.rootNodeInfo = getRootInActiveWindow();

        List<AccessibilityNodeInfo> nodes = HongbaoService.this.rootNodeInfo.findAccessibilityNodeInfosByViewId(id);
        if (nodes != null && nodes.size() > 0) {
            if (nodes.get(0).getText() != null) {
                return nodes.get(0).getText().toString();
            } else {
                return null;
            }

        }

        nodes = new ArrayList<AccessibilityNodeInfo>();
        HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, id);

        String res = "";
        if (nodes.size() > 0 && nodes.get(0) != null && nodes.get(0).getText() != null) {
            res = nodes.get(0).getText().toString();
        }

        return res;
    }

    private String[] getSellDetailInfo() {
        HongbaoService.this.rootNodeInfo = getRootInActiveWindow();


        String status = HongbaoService.this.getTextById("com.shiyebaidu.ceo:id/tv_status");

        if (status == null || "".equals(status)) {
            info(TAG, "status is empty");
            return null;
        }


        String nick = HongbaoService.this.getTextById("com.shiyebaidu.ceo:id/tv_counter_values");
        if (nick == null) {
            info(TAG, "nick is null");
            return null;
        }

        String name = HongbaoService.this.getTextById("com.shiyebaidu.ceo:id/tv_counter_true_name");
        if (name == null || "".equals(name)) {
            info(TAG, "name is null");
            return null;
        }

        String mobile = HongbaoService.this.getTextById("com.shiyebaidu.ceo:id/tv_mobile");
        if (mobile == null || "".equals(mobile)) {
            info(TAG, "mobile is null");
            return null;
        }


        String amount = HongbaoService.this.getTextById("com.shiyebaidu.ceo:id/tv_transaction_amount");
        if (amount == null || "".equals(amount)) {
            info(TAG, "amount is empty");
            return null;
        }
        amount = amount.replace("CNY", "").trim();

        String price = HongbaoService.this.getTextById("com.shiyebaidu.ceo:id/tv_price");
        if (price == null) {
            info(TAG, "price is null");
            return null;
        }
        price = price.replace("CNY", "").trim();

        String num = HongbaoService.this.getTextById("com.shiyebaidu.ceo:id/tv_num");
        if (num == null) {
            info(TAG, "num is null");
            return null;
        }
        num = num.replace("QC", "").trim();

        String tid = HongbaoService.this.getTextById("com.shiyebaidu.ceo:id/tv_trade_id");
        if (tid == null || "".equals(tid)) {
            info(TAG, "tid is null");
            return null;
        }
        tid = tid.replace("复制", "").trim();

        String time = HongbaoService.this.getTextById("com.shiyebaidu.ceo:id/tv_add_time");
        if (time == null) {
            info(TAG, "time is null");
            return null;
        }
        time = time.replace("复制", "").trim();

        String orderId = HongbaoService.this.getTextById("com.shiyebaidu.ceo:id/tv_order_id");
        if (orderId == null) {
            info(TAG, "orderId is null");
            return null;
        }
        orderId = orderId.replace("复制", "").trim();


        return new String[]{"app", "ceo", "title", "OTC.ceo", "id", tid, "trueName", name, "amount", amount, "createTime", time, "status", status};
    }

    private boolean contains(String[] arr, String tid) {
        if (arr == null)
            return false;

        for (int i = 0; i < arr.length; ++i) {
            if (tid.equals(arr[i])) {
                return true;
            }
        }

        return false;
    }

    private void scanCeoToConfirmList() {
        List<AccessibilityNodeInfo> nodes = new ArrayList<AccessibilityNodeInfo>();
        HongbaoService.this.rootNodeInfo = getRootInActiveWindow();
        HongbaoService.this.findNodesById(nodes, HongbaoService.this.rootNodeInfo, "com.shiyebaidu.ceo:id/tv_coin_name");
        if (nodes.size() > 0) {
            AccessibilityNodeInfo root = nodes.get(0).getParent().getParent().getParent().getParent();
            int count = root.getChildCount();
            for (int i = 0; i < count; ++i) {
                AccessibilityNodeInfo node = HongbaoService.this.getChild(root, i + "00");
                String nick = node.getText().toString();

                node = HongbaoService.this.getChild(root, i + "01");
                String status = node.getText().toString();

                node = HongbaoService.this.getChild(root, i + "201");
                String coin = node.getText().toString();

                node = HongbaoService.this.getChild(root, i + "202");
                String time = node.getText().toString();

                node = HongbaoService.this.getChild(root, i + "2101");
                String price = node.getText().toString();

                node = HongbaoService.this.getChild(root, i + "2111");
                String amount = node.getText().toString();

                node = HongbaoService.this.getChild(root, i + "2121");
                String total = node.getText().toString();

                String key = String.format("%s|%s|%s|%s", coin, nick, time, total);
                info(TAG, "toConfirm:" + key);
                if (ceoToConfirmList.contains(key)) {
                    info(TAG, "skipped");
                } else {
                    ceoToConfirmList.add(key);
                    info(TAG, "clicking");
                    firstTimeInOtcOrderDetail = 0;
                    node.getParent().getParent().getParent().getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    return;
                }
            }
        }

        back(500, onEventProcessed);
    }

    private synchronized void refreshBillList() {

        info(TAG, "refreshBillList");
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float dpi = metrics.densityDpi;
        info(TAG, "dpi=" + dpi);
        Path path = new Path();
        if (320 == dpi) {//720p
            Log.d(TAG, "dpi 320");
            path.moveTo(355, 355);
            path.lineTo(355, 600);
        } else if (480 == dpi) {//1080p
            Log.d(TAG, "dpi 480");
            path.moveTo(533, 533);
            path.lineTo(533, 1000);
        } else { //1440
            Log.d(TAG, "dpi 1440");
            path.moveTo(720, 720);
            path.lineTo(720, 1200);
        }

        if (android.os.Build.VERSION.SDK_INT > 23) {
            try {
                GestureDescription.Builder builder = new GestureDescription.Builder();
                GestureDescription gestureDescription = builder.addStroke(new GestureDescription.StrokeDescription(path, 1000, 200L)).build();
                dispatchGesture(gestureDescription, new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        info(TAG, "refreshBillList onCompleted");
                        mMutex = false;
                        super.onCompleted(gestureDescription);
                        sleep(3000, new Runnable() {
                            @Override
                            public void run() {
                                scanBillList();
                            }
                        });
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        info(TAG, "refreshBillList onCancelled");
                        mMutex = false;
                        super.onCancelled(gestureDescription);
                    }
                }, null);
            } catch (Exception ex) {
                ex.printStackTrace();
                sleep(2000, new Runnable() {
                    @Override
                    public void run() {
                        scanBillList();
                    }
                });
            }
        } else {
            sleep(5000, new Runnable() {
                @Override
                public void run() {
                    scanBillList();
                }
            });
        }

    }

    private void openPacket() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float dpi = metrics.densityDpi;
        Log.d(TAG, "openPacket！" + dpi);
        if (android.os.Build.VERSION.SDK_INT <= 23) {
            mUnpackNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } else {
            if (android.os.Build.VERSION.SDK_INT > 23) {
                Path path = new Path();
                if (640 == dpi) { //1440
                    path.moveTo(720, 1575);
                } else if (320 == dpi) {//720p
                    path.moveTo(355, 780);
                } else if (480 == dpi) {//1080p
                    path.moveTo(533, 1115);
                }
                GestureDescription.Builder builder = new GestureDescription.Builder();
                GestureDescription gestureDescription = builder.addStroke(new GestureDescription.StrokeDescription(path, 450, 50)).build();
                dispatchGesture(gestureDescription, new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        Log.d(TAG, "onCompleted");
                        mMutex = false;
                        super.onCompleted(gestureDescription);
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        Log.d(TAG, "onCancelled");
                        mMutex = false;
                        super.onCancelled(gestureDescription);
                    }
                }, null);

            }
        }
    }

    private void setCurrentActivityName(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }

        try {
            ComponentName componentName = new ComponentName(
                    event.getPackageName().toString(),
                    event.getClassName().toString()
            );

            getPackageManager().getActivityInfo(componentName, 0);
            currentActivityName = componentName.flattenToShortString();
        } catch (PackageManager.NameNotFoundException e) {
            currentActivityName = WECHAT_LUCKMONEY_GENERAL_ACTIVITY;
        }
    }

    private boolean watchList(AccessibilityEvent event) {
        if (mListMutex) return false;
        mListMutex = true;
        AccessibilityNodeInfo eventSource = event.getSource();
        // Not a message
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || eventSource == null)
            return false;

        List<AccessibilityNodeInfo> nodes = eventSource.findAccessibilityNodeInfosByText(WECHAT_NOTIFICATION_TIP);
        //增加条件判断currentActivityName.contains(WECHAT_LUCKMONEY_GENERAL_ACTIVITY)
        //避免当订阅号中出现标题为“[微信红包]拜年红包”（其实并非红包）的信息时误判
        if (!nodes.isEmpty() && currentActivityName.contains(WECHAT_LUCKMONEY_GENERAL_ACTIVITY)) {
            AccessibilityNodeInfo nodeToClick = nodes.get(0);
            if (nodeToClick == null) return false;
            CharSequence contentDescription = nodeToClick.getContentDescription();
            if (contentDescription != null && !signature.getContentDescription().equals(contentDescription)) {
                nodeToClick.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                signature.setContentDescription(contentDescription.toString());
                return true;
            }
        }
        return false;
    }

    private boolean watchNotifications(AccessibilityEvent event) {
        Log.d(TAG, "watch notification");
        // Not a notification
        if (event.getEventType() != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)
            return false;

        if (event.getPackageName() == null) return true;


        if (event.getPackageName().toString().contains(CeoPackageName)) {
            Parcelable parcelable = event.getParcelableData();
            if (parcelable instanceof Notification) {
                final Notification notification = (Notification) parcelable;
                try {

                    HongbaoService.this.contentIntent = notification.contentIntent;
                    Intent intent = getIntent(HongbaoService.this.contentIntent);
                    String action = intent.getAction();
                    RemoteViews views = notification.contentView;
                    List<String> texts = getTexts(views);

                    if (texts.size() > 0) {
                        String text = texts.get(0);
                        if (text.contains("待付款") || text.contains("待确认")) {
                            //HongbaoService.this.powerUtil.handleWakeLock(true);
                            //HongbaoService.this.contentIntent.send();
                            info(TAG, "adding ceo.otc:" + text);
                            HongbaoService.this.notifications.add("ceo.otc:" + text);
                        }
                    }

                    /*
                    Bundle b = intent.getExtras();
                    Set<String> keys = b.keySet();
                    Iterator<String> iterator = keys.iterator();
                    while (iterator.hasNext()) {
                        String key = iterator.next();
                        String value = b.getString(key);
                        info(TAG, key + "=" + value);
                    }
                    */
                } catch (Exception ex) {
                }
            }
        } else if (event.getPackageName().toString().contains(AlipayPackageName)) {
            String tip = event.getText().get(0).toString();
            if (!tip.contains(Alipay_NOTIFICATION_TIP)
                    && !tip.contains("成功收款")
                    && !tip.contains("向你付款")
                    ) return true;

            info(TAG, "adding alipay:" + tip);
            synchronized (HongbaoService.this.notifications) {
                HongbaoService.this.notifications.add("alipay:" + tip);
            }
        }

        processEvents();

        return true;
    }

    private void processEvents() {
        if (isProcessingEvents) {
            return;
        }

        if (notifications.size() == 0) {
            HongbaoService.this.powerUtil.handleWakeLock(false);
            return;
        }

        isProcessingEvents = true;
        HongbaoService.this.notificationText = HongbaoService.this.notifications.poll();
        info(TAG, "processing " + HongbaoService.this.notificationText);
        processEvent();
    }

    private void processEvent() {
        if (HongbaoService.this.notificationText.startsWith("ceo")) {
            startCeo();
        } else if (HongbaoService.this.notificationText.startsWith("alipay")) {
            isProcessingEvents = false;
            notificationText = null;
            processEvents();
            //startAlipay();
        }

    }

    private void startCeo() {
        firstTimeInOtcBusiness0 = 0;
        firstTimeInOtcBusiness1 = 0;
        firstTimeInOtcBusiness2 = 0;
        firstTimeInOtcBusiness3 = 0;
        firstTimeInOtcBusiness4 = 0;
        firstTimeInOtcOrderDetail = 0;
        otcMenuBusinessClicked = 0;
        otcMenuClicked = 0;
        ceoToConfirmList.clear();
        HongbaoService.this.powerUtil.handleWakeLock(true);
        //performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
        sleep(500, new Runnable() {
            @Override
            public void run() {
                Intent intent = getApplicationContext().getPackageManager().getLaunchIntentForPackage("com.shiyebaidu.ceo");
                //intent.setClassName("com.eg.android.AlipayGphone", "com.alipay.mobile.transferapp.ui.TransferToCardFormActivity_");
                intent.setAction("android.intent.action.MAIN");
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                //intent.putExtra("data", "在此处添加数据信息");
                startActivity(intent);
            }
        });
    }

    private void startCeoOrderActivity() {

        Intent intent = new Intent();
        intent.setAction("android.intent.action.MAIN");
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        //intent.setClassName("com.shiyebaidu.ceo.business.otc.activity", "OTCOrderDetailActivity");
        intent.setComponent(new ComponentName("com.shiyebaidu.ceo", "com.shiyebaidu.ceo.business.otc.activity.OTCOrderDetailActivity"));
        intent.putExtra("tradeno", "15612003516049181491");
        startActivity(intent);
    }

    /*打开app*/
    @TargetApi(Build.VERSION_CODES.DONUT)
    private void doStartApplicationWithPackageName(String packagename) {

        // 通过包名获取此APP详细信息，包括Activities、services、versioncode、name等等
        PackageInfo packageinfo = null;
        try {
            packageinfo = getPackageManager().getPackageInfo(packagename, 0);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        if (packageinfo == null) {
            return;
        }

        // 创建一个类别为CATEGORY_LAUNCHER的该包名的Intent
        Intent resolveIntent = new Intent(Intent.ACTION_MAIN, null);
        resolveIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        resolveIntent.setPackage(packageinfo.packageName);

        // 通过getPackageManager()的queryIntentActivities方法遍历
        List<ResolveInfo> resolveinfoList = getPackageManager()
                .queryIntentActivities(resolveIntent, 0);
        Iterator<ResolveInfo> it = resolveinfoList.iterator();
        ResolveInfo resolveinfo = it.next();
        while (resolveinfo != null) {
            // packagename = 参数packname
            String packageName = resolveinfo.activityInfo.packageName;
            // 这个就是我们要找的该APP的LAUNCHER的Activity[组织形式：packagename.mainActivityname]
            String className = resolveinfo.activityInfo.name;
            // LAUNCHER Intent
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // 设置ComponentName参数1:packagename参数2:MainActivity路径
            ComponentName cn = new ComponentName(packageName, className);

            intent.setComponent(cn);
            startActivity(intent);
            resolveinfo = null;
            if (it.hasNext()) {
                resolveinfo = it.next();
            }
        }
    }

    private List<String> getTexts(RemoteViews views) {
        List<String> text = new ArrayList<String>();
        try {
            Field field = views.getClass().getDeclaredField("mActions");
            field.setAccessible(true);

            @SuppressWarnings("unchecked")
            ArrayList<Parcelable> actions = (ArrayList<Parcelable>) field.get(views);

            // Find the setText() and setTime() reflection actions
            for (Parcelable p : actions) {
                Parcel parcel = Parcel.obtain();
                p.writeToParcel(parcel, 0);
                parcel.setDataPosition(0);

                // The tag tells which type of action it is (2 is ReflectionAction, from the source)
                int tag = parcel.readInt();
                if (tag != 2) continue;

                // View ID
                parcel.readInt();

                String methodName = parcel.readString();
                if (methodName == null) continue;

                    // Save strings
                else if (methodName.equals("setText")) {
                    // Parameter type (10 = Character Sequence)
                    parcel.readInt();

                    // Store the actual string
                    String t = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel).toString().trim();
                    text.add(t);
                }

                // Save times. Comment this section out if the notification time isn't important
                else if (methodName.equals("setTime")) {
                    // Parameter type (5 = Long)
                    parcel.readInt();

                    String t = new SimpleDateFormat("h:mm a").format(new Date(parcel.readLong()));
                    text.add(t);
                }

                parcel.recycle();
            }
        }

        // It's not usually good style to do this, but then again, neither is the use of reflection...
        catch (Exception e) {
            Log.e("NotificationClassifier", e.toString());
        }

        return text;
    }

    @Override
    public void onInterrupt() {
        info(TAG, "onInterrupt");
        HongbaoService.singleton = null;
    }

    public Intent getIntent(PendingIntent pendingIntent) throws IllegalStateException {
        try {
            Method getIntent = PendingIntent.class.getDeclaredMethod("getIntent");
            return (Intent) getIntent.invoke(pendingIntent);
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private AccessibilityNodeInfo findOpenButton(AccessibilityNodeInfo node) {
        if (node == null)
            return null;

        //非layout元素
        if (node.getChildCount() == 0) {
            if ("android.widget.Button".equals(node.getClassName()))
                return node;
            else
                return null;
        }

        //layout元素，遍历找button
        AccessibilityNodeInfo button;
        for (int i = 0; i < node.getChildCount(); i++) {
            button = findOpenButton(node.getChild(i));
            if (button != null)
                return button;
        }
        return null;
    }

    private void checkNodeInfoWechat(int eventType) {
        if (HongbaoService.this.rootNodeInfo == null) return;

        if (signature.commentString != null) {
            sendComment();
            signature.commentString = null;
        }

        /* 聊天会话窗口，遍历节点匹配“领取红包”和"查看红包" */
        AccessibilityNodeInfo node1 = (sharedPreferences.getBoolean("pref_watch_self", false)) ?
                HongbaoService.this.getTheLastNode(WECHAT_VIEW_OTHERS_CH, WECHAT_VIEW_SELF_CH) : HongbaoService.this.getTheLastNode(WECHAT_VIEW_OTHERS_CH);
        if (node1 != null &&
                (currentActivityName.contains(WECHAT_LUCKMONEY_CHATTING_ACTIVITY)
                        || currentActivityName.contains(WECHAT_LUCKMONEY_GENERAL_ACTIVITY))) {
            String excludeWords = sharedPreferences.getString("pref_watch_exclude_words", "");
            if (HongbaoService.this.signature.generateSignature(node1, excludeWords)) {
                mLuckyMoneyReceived = true;
                mReceiveNode = node1;
                Log.d("sig", HongbaoService.this.signature.toString());
            }
            return;
        }

        /* 戳开红包，红包还没抢完，遍历节点匹配“拆红包” */
        AccessibilityNodeInfo node2 = findOpenButton(HongbaoService.this.rootNodeInfo);
        Log.d(TAG, "checkNodeInfo  node2 " + node2);
        if (node2 != null && "android.widget.Button".equals(node2.getClassName()) && currentActivityName.contains(WECHAT_LUCKMONEY_RECEIVE_ACTIVITY)
                && (mUnpackNode == null || mUnpackNode != null && !mUnpackNode.equals(node2))) {
            mUnpackNode = node2;
            mUnpackCount += 1;
            return;
        }

        /* 戳开红包，红包已被抢完，遍历节点匹配“红包详情”和“手慢了” */
        boolean hasNodes = HongbaoService.this.hasOneOfThoseNodes(
                WECHAT_BETTER_LUCK_CH, WECHAT_DETAILS_CH,
                WECHAT_BETTER_LUCK_EN, WECHAT_DETAILS_EN, WECHAT_EXPIRES_CH);
        Log.d(TAG, "checkNodeInfo  hasNodes:" + hasNodes + " mMutex:" + mMutex);
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && hasNodes
                && (currentActivityName.contains(WECHAT_LUCKMONEY_DETAIL_ACTIVITY)
                || currentActivityName.contains(WECHAT_LUCKMONEY_RECEIVE_ACTIVITY))) {
            mMutex = false;
            mLuckyMoneyPicked = false;
            mUnpackCount = 0;
            back(500);
            signature.commentString = generateCommentString();
        }
    }

    private void home(long ms) {
        sleep(ms, new Runnable() {
            @Override
            public void run() {

                info(TAG, "press home");
                performGlobalAction(GLOBAL_ACTION_HOME);
            }
        });
    }

    private void back(long ms) {
        sleep(ms, new Runnable() {
            @Override
            public void run() {
                info(TAG, "press back");
                performGlobalAction(GLOBAL_ACTION_BACK);

            }
        });
    }

    private void back(long ms, final Runnable run) {
        sleep(ms, new Runnable() {
            @Override
            public void run() {
                info(TAG, "press back");
                performGlobalAction(GLOBAL_ACTION_BACK);
                if (run != null) {
                    run.run();
                }
            }
        });
    }

    private void sleep(long ms, Runnable r) {
        new android.os.Handler().postDelayed(r, ms);
    }

    private void sendIntent(String[] info) {
        String msg = "";
        for (int i = 0; i < info.length; i = i + 2) {
            msg += info[i] + ":" + info[i + 1] + ",";
        }

        info(TAG, "sending notification:" + msg);
        Intent intent = new Intent();
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        intent.setAction("com.darryncampbell.cordova.plugin.broadcastIntent.ACTION");
        for (int i = 0; i < info.length; i = i + 2) {
            intent.putExtra(info[i], info[i + 1]);
        }

        HongbaoService.this.sendBroadcast(intent);
    }

    /*not used*/
    private void sendNotification(String amount, String reference, String name, String mobile) {
        info(TAG, "sending notification");
        if ("".equals(amount + reference + name + mobile)) {
            info(TAG, "skip sending noti");
            return;
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setContentTitle("支付宝转账监控")
                .setSmallIcon(R.mipmap.icon_alipay)
                .setContentText(amount + ":" + reference + ":" + name + ":" + mobile)
                .setContentIntent(HongbaoService.this.contentIntent);

        HongbaoService.this.notificationText = null;
        final Notification notification = builder.build();
        //notification.flags = Notification.FLAG_ONGOING_EVENT;

        final int nid = HongbaoService.this.nid++;
        HongbaoService.this.notificationText = null;
        //int delayFlag = sharedPreferences.getInt("pref_open_delay", 0) * 1000;

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        notificationManager.notify(nid, notification);
    }

    private void checkNodeInfo(int eventType) {
        if (HongbaoService.this.rootNodeInfo == null) return;

        AccessibilityNodeInfo node1 = HongbaoService.this.getTheLastNode("com.alipay.mobile.chatapp:id/biz_desc");
        if (node1 != null) {
            String text = node1.getText().toString();
            text = text.substring(0, text.length() - 1);
            try {
                float amount = Float.parseFloat(text);
                Log.d(TAG, "收到钱数:" + amount);

                node1 = HongbaoService.this.getTheLastNode("com.alipay.mobile.chatapp:id/biz_title");
                String title = node1.getText().toString();
                HongbaoService.this.sendNotification(text, "", title, "");
                performGlobalAction(GLOBAL_ACTION_HOME);
                HongbaoService.this.powerUtil.handleWakeLock(false);
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            return;
        }

        /* 戳开红包，红包还没抢完，遍历节点匹配“拆红包” */
        //AccessibilityNodeInfo node2 = findOpenButton(HongbaoService.this.rootNodeInfo);

    }

    private void sendComment() {
        try {
            AccessibilityNodeInfo outNode =
                    getRootInActiveWindow().getChild(0).getChild(0);
            AccessibilityNodeInfo nodeToInput = outNode.getChild(outNode.getChildCount() - 1).getChild(0).getChild(1);

            if ("android.widget.EditText".equals(nodeToInput.getClassName())) {
                Bundle arguments = new Bundle();
                arguments.putCharSequence(AccessibilityNodeInfo
                        .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, signature.commentString);
                nodeToInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
            }
        } catch (Exception e) {
            // Not supported
        }
    }


    private boolean hasOneOfThoseNodes(String... texts) {
        List<AccessibilityNodeInfo> nodes;
        for (String text : texts) {
            if (text == null) continue;

            nodes = HongbaoService.this.rootNodeInfo.findAccessibilityNodeInfosByText(text);

            if (nodes != null && !nodes.isEmpty()) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo getTheLastNode(String... texts) {
        int bottom = 0;
        AccessibilityNodeInfo lastNode = null, tempNode;
        List<AccessibilityNodeInfo> nodes;

        for (String text : texts) {
            if (text == null) continue;

            nodes = HongbaoService.this.rootNodeInfo.findAccessibilityNodeInfosByViewId(text);//biz_title;//.findAccessibilityNodeInfosByText(text)

            if (nodes != null && !nodes.isEmpty()) {
                tempNode = nodes.get(nodes.size() - 1);
                if (tempNode == null) return null;
                Rect bounds = new Rect();
                tempNode.getBoundsInScreen(bounds);
                if (bounds.bottom > bottom) {
                    bottom = bounds.bottom;
                    lastNode = tempNode;
                    signature.others = text.equals(WECHAT_VIEW_OTHERS_CH);
                }
            }
        }
        return lastNode;
    }

    @Override
    public void onServiceConnected() {
        info(TAG, "service connected");
        //startCeoOrderActivity();
        //doStartApplicationWithPackageName(CeoPackageName);
        super.onServiceConnected();
        HongbaoService.this.watchFlagsFromPreference();

        registerClipEvents();
        HongbaoService.this.notificationText = null;
        HongbaoService.this.shouldInBillInfo = 0;
        firstTimeInOtcBusiness0 = 0;
        firstTimeInOtcOrderDetail = 0;
        HongbaoService.this.bills.clear();
        onEventProcessed = new Runnable() {
            @Override
            public void run() {
                HongbaoService.this.notificationText = null;
                HongbaoService.this.isProcessingEvents = false;
                processEvents();
            }
        };
        sleep(500, new Runnable() {
            @Override
            public void run() {
                showReminder();
            }
        });

        sleep(2000, new Runnable() {
            @Override
            public void run() {
                autoWatch();
                HongbaoService.singleton = HongbaoService.this;

                singleton.notifications.add("alipay:scan");

                singleton.notifications.add("ceo:scan");
                singleton.processEvents();
            }
        });


    }


    private void autoWatch() {
        int interval = sharedPreferences.getInt("pref_open_delay", 0);

        if (interval > 0) {
            if (HongbaoService.this.timer == null) {
            } else {
                HongbaoService.this.timer.cancel();
            }

            HongbaoService.this.timer = new Timer();

            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    singleton.notifications.add("alipay:scan");
                    singleton.processEvents();
                }
            }, 5000, interval * 60000);
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    singleton.notifications.add("ceo:scan");
                    singleton.processEvents();
                }
            }, 5000 + 60000, interval * 60000);

        } else {
            if (HongbaoService.this.timer != null) {
                HongbaoService.this.timer.cancel();
                HongbaoService.this.timer = null;
            }
        }
    }

    private void registerClipEvents() {

        final ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

        manager.addPrimaryClipChangedListener(new ClipboardManager.OnPrimaryClipChangedListener() {
            @Override
            public void onPrimaryClipChanged() {

                try {
                    if (manager.hasPrimaryClip() && manager.getPrimaryClip().getItemCount() > 0) {

                        CharSequence text = manager.getPrimaryClip().getItemAt(0).getText();

                        if (text != null) {
                            info(TAG, "copied text: " + text);

                            if (payInfo == null) {

                                JSONObject json = new JSONObject(text.toString());
                                payInfo = json;
                                if (!payInfo.optString("type").equals("wechat")) {
                                    startAlipay();
                                }

                            }
                        }
                    }
                } catch (Exception ex) {
                    //ex.printStackTrace();
                }
            }
        });
    }

    private void startAlipay() {

        HongbaoService.this.billListRefreshed = 0;
        HongbaoService.this.firstTimeInBillList = 0;
        HongbaoService.this.firstTimeInMineView = 0;
        HongbaoService.this.manualStart = 0;
        HongbaoService.this.shouldInBillInfo = 0;
        HongbaoService.this.backedFromBusiness = 0;
        HongbaoService.this.backedFromChat = 0;

        HongbaoService.this.powerUtil.handleWakeLock(true);
        //performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
        sleep(500, new Runnable() {
            @Override
            public void run() {
                Intent intent = getApplicationContext().getPackageManager().getLaunchIntentForPackage("com.eg.android.AlipayGphone");
                //intent.setClassName("com.eg.android.AlipayGphone", "com.alipay.mobile.transferapp.ui.TransferToCardFormActivity_");
                intent.setAction("android.intent.action.MAIN");
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                //intent.putExtra("data", "在此处添加数据信息");
                startActivity(intent);
            }
        });
    }

    public void info(String tag, String message) {
        Log.i(tag, message);
        Intent intent = new Intent();
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        intent.setAction("com.darryncampbell.cordova.plugin.broadcastIntent.ACTION");
        long tid = Thread.currentThread().getId();
        intent.putExtra("message", "[" + tid + "]" + message);
        sendBroadcast(intent);
    }

    private void showReminder() {
        info(TAG, "show reminder");
        Intent intent = getApplicationContext().getPackageManager().getLaunchIntentForPackage("com.lbdd.reminder");
        //intent.setClassName("com.eg.android.AlipayGphone", "com.alipay.mobile.transferapp.ui.TransferToCardFormActivity_");
        intent.setAction("android.intent.action.MAIN");
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        //intent.putExtra("data", "在此处添加数据信息");
        startActivity(intent);
    }

    private void watchFlagsFromPreference() {

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);

        HongbaoService.this.powerUtil = new PowerUtil(this);
        Boolean watchOnLockFlag = sharedPreferences.getBoolean("pref_watch_on_lock", false);
        HongbaoService.this.powerUtil.handleWakeLock(watchOnLockFlag);
    }

    private void checkAlipay(int manualStart) {
        HongbaoService.this.notificationText = "alipay:test";
        backedFromChat = 0;
        backedFromBusiness = 0;
        firstTimeInMineView = 0;
        firstTimeInBillList = 0;
        shouldInBillInfo = 0;
        shouldInSenderAccount = 0;
        trueName = null;
        HongbaoService.this.isProcessingEvents = true;
        HongbaoService.this.manualStart = manualStart;
        HongbaoService.this.powerUtil.handleWakeLock(true);
        showReminder();
        startAlipay();
    }

    private void checkCeo() {
        HongbaoService.this.notificationText = "ceo:test";
        HongbaoService.this.isProcessingEvents = true;
        startCeo();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equals("pref_watch_on_lock")) {
            Boolean changedValue = sharedPreferences.getBoolean(key, false);
            HongbaoService.this.powerUtil.handleWakeLock(changedValue);
            if (changedValue == false) {
                //this.notifications.add("alipay:scan");
            } else {
                this.notifications.add("ceo:scan");
                //sendIntent(new String[]{"type", "test", "data", "test"});
            }
            processEvents();
        } else if (key.equals("pref_open_delay")) {
            autoWatch();
        }
    }

    @Override
    public void onDestroy() {
        HongbaoService.this.powerUtil.handleWakeLock(false);
        super.onDestroy();
    }

    private String generateCommentString() {
        if (!signature.others) return null;

        Boolean needComment = sharedPreferences.getBoolean("pref_comment_switch", false);
        if (!needComment) return null;

        String[] wordsArray = sharedPreferences.getString("pref_comment_words", "").split(" +");
        if (wordsArray.length == 0) return null;

        Boolean atSender = sharedPreferences.getBoolean("pref_comment_at", false);
        if (atSender) {
            return "@" + signature.sender + " " + wordsArray[(int) (Math.random() * wordsArray.length)];
        } else {
            return wordsArray[(int) (Math.random() * wordsArray.length)];
        }
    }
}