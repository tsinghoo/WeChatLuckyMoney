package xyz.monkeytong.hongbao.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Parcelable;
import android.graphics.Path;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.DisplayMetrics;

import java.util.ArrayList;

import xyz.monkeytong.hongbao.R;
import xyz.monkeytong.hongbao.utils.HongbaoSignature;
import xyz.monkeytong.hongbao.utils.PowerUtil;

import android.app.NotificationManager;
import android.content.Context;
import android.support.v4.app.NotificationCompat;

import org.json.JSONObject;

import java.util.Date;
import java.util.List;

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
    private static final String AlipayTransfer = "转账";
    private static final String AlipayTransferToYou = "转账给你";
    private static final String WECHAT_LUCKMONEY_RECEIVE_ACTIVITY = ".plugin.luckymoney.ui";//com.tencent.mm/.plugin.luckymoney.ui.En_fba4b94f  com.tencent.mm/com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyReceiveUI
    private static final String WECHAT_LUCKMONEY_DETAIL_ACTIVITY = "LuckyMoneyDetailUI";
    private static final String WECHAT_LUCKMONEY_GENERAL_ACTIVITY = "LauncherUI";
    private static final String WECHAT_LUCKMONEY_CHATTING_ACTIVITY = "ChattingUI";
    private String currentActivityName = WECHAT_LUCKMONEY_GENERAL_ACTIVITY;

    private AccessibilityNodeInfo rootNodeInfo, mReceiveNode, mUnpackNode;
    private boolean mLuckyMoneyPicked, mLuckyMoneyReceived;
    private int mUnpackCount = 0;
    private boolean mMutex = false, mListMutex = false, mChatMutex = false;
    private HongbaoSignature signature = new HongbaoSignature();

    private List<String> bills = new java.util.ArrayList<String>();
    private List<String> messages = new ArrayList<String>();
    private PowerUtil powerUtil;
    private SharedPreferences sharedPreferences;
    private int nid = 1;
    private long firstTimeInBillList = 0;
    private int billListRefreshed = 0;
    private int billInfoGot = 0;
    private int firstTimeInMineView = 0;
    private PendingIntent contentIntent;
    private String notificationText = null;
    private int backedFromBusiness = 0;
    private int backedFromChat = 0;
    private JSONObject payInfo = null;
    private java.util.concurrent.ConcurrentLinkedQueue notifications = new java.util.concurrent.ConcurrentLinkedQueue<String>();

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
            if (sharedPreferences.getBoolean("pref_watch_list", false) && watchList(event)) return;
            mListMutex = false;
        }

        if (!mChatMutex) {
            mChatMutex = true;
            if (sharedPreferences.getBoolean("pref_watch_chat", false)) watchChat(event);
            mChatMutex = false;
        }
    }


    private void watchChatV1(AccessibilityEvent event) {
        if (this.notificationText == null) return; //not open through notification;
        this.rootNodeInfo = getRootInActiveWindow();

        if (rootNodeInfo == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        String cName = event.getClassName().toString();

        Log.i("className", "className:" + cName);
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
        for (int i = 0; i < root.getChildCount(); ++i) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child == null) {
                continue;
            }

            String rid = child.getViewIdResourceName();
            if (rid != null && rid.equals(id)) {
                nodes.add(child);
            } else {
                this.findNodesById(nodes, child, id);
            }
        }

        if (nodes.size() > 0) {
            return true;
        }

        return false;
    }

    private boolean isInChat(List<AccessibilityNodeInfo> nodes) {
        nodes.clear();
        AccessibilityNodeInfo node = this.getTheLastNode("com.alipay.mobile.chatapp:id/biz_desc");
        if (node != null) {
            nodes.add(node);
            return true;
        }

        return false;
    }

    private boolean isInBill(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo lastNode = null, node;
        nodes.clear();
        if (this.findNodesById(nodes, this.rootNodeInfo, "com.alipay.mobile.nebula:id/h5_tv_title")) {
            if ("账单详情".equals(nodes.get(0).getText())) {
                nodes.clear();
                this.findNodesById(nodes, this.rootNodeInfo, "com.alipay.mobile.nebula:id/h5_pc_container");
                return true;
            }
        }

        return false;
    }

    private boolean isInBillList(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo lastNode = null, node;
        nodes.clear();
        if (this.findNodesById(nodes, this.rootNodeInfo, "com.alipay.mobile.bill.list:id/listItem")) {

            return true;
        }


        return false;
    }

    private boolean isInBusiness(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo lastNode = null, node;
        nodes.clear();
        if (this.findNodesById(nodes, this.rootNodeInfo, "com.alipay.mobile.nebula:id/h5_tv_title")) {

            if ("商家服务".equals(nodes.get(0).getText())) {
                return true;
            }
        }
        return false;
    }

    private boolean billExist(String bill) {
        for (int i = 0; i < this.bills.size(); ++i) {
            if (bills.get(i).equals(bill)) {
                return true;
            }
        }

        return false;
    }

    private boolean clickBill(AccessibilityNodeInfo node) {
        List<AccessibilityNodeInfo> items = new java.util.ArrayList<AccessibilityNodeInfo>();
        String bill = "";
        if (this.findNodesById(items, node, "com.alipay.mobile.bill.list:id/billName")) {
            bill = bill + items.get(0).getText().toString();
        }
        items.clear();
        if (this.findNodesById(items, node, "com.alipay.mobile.bill.list:id/billAmount")) {
            bill = bill + items.get(0).getText().toString();
        }
        items.clear();
        if (this.findNodesById(items, node, "com.alipay.mobile.bill.list:id/timeInfo1")) {
            bill = bill + items.get(0).getText().toString();
        }
        items.clear();
        if (this.findNodesById(items, node, "com.alipay.mobile.bill.list:id/timeInfo2")) {
            bill = bill + items.get(0).getText().toString();
        }
        items.clear();

        if (billExist(bill)) {
            Log.i(TAG, "bill already clicked");
            return false;
        } else {
            this.bills.add(bill);
            this.billInfoGot = 0;
            Log.i(TAG, "clicking bill");
            node.getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
            return true;
        }
    }

    private boolean isInMineView(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo lastNode = null, node;
        nodes.clear();

        if (this.findNodesById(nodes, this.rootNodeInfo, "com.alipay.mobile.antui:id/item_left_text")) {
            if (nodes.size() > 2 && nodes.get(2).getText().equals("账单")) {
                return true;
            }
        }

        return false;
    }

    private boolean isInFirstPage(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo lastNode = null, node;
        nodes.clear();

        if (this.findNodesById(nodes, this.rootNodeInfo, "com.alipay.android.phone.openplatform:id/app_text")) {
            if (nodes.size() > 2 && nodes.get(1).getText().equals("转账")) {
                return true;
            }
        }

        return false;
    }

    private boolean isInTransferPage(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo lastNode = null, node1 = null, node2 = null;
        nodes.clear();

        if (this.findNodesById(nodes, this.rootNodeInfo, "com.alipay.mobile.transferapp:id/to_account_view")) {
            if (nodes.size() > 0) {
                node1 = nodes.get(0);
            }
        }

        nodes.clear();

        if (this.findNodesById(nodes, this.rootNodeInfo, "com.alipay.mobile.transferapp:id/to_card_view")) {
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

        if (this.findNodesById(nodes, this.rootNodeInfo, "com.alipay.mobile.transferapp:id/tf_toAccountNextBtn")) {
            if (nodes.size() > 0) {
                node1 = nodes.get(0);
            }
        }

        nodes.clear();

        if (this.findNodesById(nodes, this.rootNodeInfo, "com.alipay.mobile.antui:id/input_edit")) {
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

        if (this.findNodesById(nodes, this.rootNodeInfo, "com.alipay.mobile.antui:id/amount_edit")) {
            if (nodes.size() > 0) {
                node1 = nodes.get(0);
            }
        }

        nodes.clear();

        if (this.findNodesById(nodes, this.rootNodeInfo, "com.alipay.mobile.transferapp:id/tf_nextBtn")) {
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

        if (this.findNodesById(nodes, this.rootNodeInfo, "com.alipay.mobile.ui:id/content")) {

            if (this.findNodesById(nodes, this.rootNodeInfo, "com.alipay.mobile.transferapp:id/btn_next")) {
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


        int i = Integer.parseInt(index.substring(0, 1));
        if (root.getChildCount() <= i) {
            return null;
        }

        if (index.length() == 1) {
            return root.getChild(i);
        }

        return this.getChild(root.getChild(i), index.substring(1, index.length()));
    }

    private synchronized void scanBillList() {

        if (firstTimeInBillList == 0) {
            Log.i(TAG, "to scan bill list");
            firstTimeInBillList = 1;
        } else {
            return;
        }

        try {
            List<AccessibilityNodeInfo> nodes = new java.util.ArrayList<AccessibilityNodeInfo>();
            if (isInBillList(nodes)) {
                for (int i = 0; i < nodes.size() && i < 3; ++i) {
                    AccessibilityNodeInfo node = nodes.get(i);
                    Log.i(TAG, "checking item " + i);
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
                sendAllNotification();
                synchronized (this.notifications) {
                    if (this.notifications.size() > 0) {
                        this.notificationText = (String) this.notifications.poll();
                        this.notifications.clear();
                        back(200);
                        return;
                    }
                }
                back(500);
                back(500);
                //back(500);
                //home(500);
                sleep(500);
                showReminder();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private void getBillInfo() {

        List<AccessibilityNodeInfo> nodes = new java.util.ArrayList<AccessibilityNodeInfo>();
        Log.i(TAG, "getting bill info");
        if (isInBill(nodes)) {
            Log.i(TAG, "getting bill info: is in bill");
            AccessibilityNodeInfo root = nodes.get(0);
            AccessibilityNodeInfo node = getChild(root, "00000");
            if (node != null) {
                String clsName = node.getClassName().toString();
                String amount = "", name = "", mobile = "", reference = "";
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
                }

                if ("".equals(amount + reference + name + mobile)) {
                    Log.i(TAG, "bill info not ready");
                    return;
                }

                amount = amount.replaceAll(",", "");
                this.billInfoGot = 1;
                synchronized (this) {
                    if (notificationText != null) {
                        saveNotification(amount, reference, name, mobile);
                        firstTimeInBillList = 0;
                        back(500);
                    }
                }
            }

        }

    }

    private void watchChat(AccessibilityEvent event) {
        if (this.notificationText == null && this.payInfo == null)
            return; //not open through notification;
        this.rootNodeInfo = getRootInActiveWindow();

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
            if (this.payInfo != null) {
                back(500);
                return;
            }
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                Log.i(TAG, "is in bill list");
                if (billListRefreshed == 0) {
                    Log.i(TAG, "to refresh bill list");
                    billListRefreshed = 1;
                    this.refreshBillList(1000);
                } else {
                    scanBillList();
                }
            }
        } else if (isInChat(nodes)) {
            Log.i(TAG, "is in chat");

            if (this.payInfo != null) {
                back(500);
                return;
            }
            if (this.backedFromChat == 0) {
                this.backedFromChat = 1;
                back(1000);
            }
        } else if (isInBusiness(nodes)) {
            Log.d(TAG, "is in business");
            if (this.payInfo != null) {
                back(500);
                return;
            }
            if (this.backedFromBusiness == 0) {
                Log.i(TAG, "back from business");
                this.backedFromBusiness = 1;
                back(1500);
            }
        } else if (isInBill(nodes)) {
            Log.i(TAG, "is in bill");
            if (this.payInfo != null) {
                back(500);
                return;
            }
            if (this.billInfoGot == 0) {
                Log.i(TAG, "to get bill info");
                this.getBillInfo();
                return;
            }

        } else if (isInTransferPage(nodes)) {
            Log.i(TAG, "is in first page to click transfer app");
            if (this.payInfo != null) {
                if (this.payInfo.optString("type").equals("alipay")) {
                    nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                } else {
                    nodes.get(1).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
            } else {
                back(500);
            }
        } else if (isInTransferToAccountPage(nodes)) {
            Log.i(TAG, "is in first page to click transfer app");
            if (this.payInfo != null) {
                ClipboardManager clipboard = (ClipboardManager) this.getSystemService(Context.CLIPBOARD_SERVICE);
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
            Log.i(TAG, "is in first page to click transfer app");
            if (this.payInfo != null) {
                ClipboardManager clipboard = (ClipboardManager) this.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("text", payInfo.optString("amount", "0.01"));
                clipboard.setPrimaryClip(clip);

                nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_PASTE);
                nodes.get(1).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                sleep(3000);


                this.rootNodeInfo = getRootInActiveWindow();
                nodes.clear();
                if (this.findNodesById(nodes, this.rootNodeInfo, "com.alipay.mobile.antui:id/ensure")) {
                    AccessibilityNodeInfo node = getChild(nodes.get(0).getParent().getParent(), "10");
                    if (node != null) {
                        clip = ClipData.newPlainText("text", payInfo.optString("name").substring(0, 1));
                        clipboard.setPrimaryClip(clip);

                        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                        node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                        nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                }

                payInfo = null;
            } else {
                back(500);
            }
        } else if (isInInputToCard(nodes)) {
            Log.i(TAG, "is in card info page");
            if (this.payInfo != null) {
                if (nodes.get(0).getText().toString().indexOf("收款人姓名") >= 0) {
                    ClipboardManager clipboard = (ClipboardManager) this.getSystemService(Context.CLIPBOARD_SERVICE);
                    int i = 0;

                    ClipData clip = ClipData.newPlainText("text", payInfo.optString("name", ""));
                    clipboard.setPrimaryClip(clip);
                    nodes.get(i).performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    nodes.get(i++).performAction(AccessibilityNodeInfo.ACTION_PASTE);
                    sleep(1000);

                    clip = ClipData.newPlainText("text", payInfo.optString("card", ""));
                    clipboard.setPrimaryClip(clip);
                    nodes.get(i).performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    nodes.get(i++).performAction(AccessibilityNodeInfo.ACTION_PASTE);
                    sleep(1000);

                    clip = ClipData.newPlainText("text", payInfo.optString("amount", ""));
                    clipboard.setPrimaryClip(clip);
                    nodes.get(i).performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    nodes.get(i++).performAction(AccessibilityNodeInfo.ACTION_PASTE);
                    payInfo = null;
                }
            } else {
                back(500);
            }
        } else {
            if (this.payInfo != null) {
                if (isInFirstPage(nodes)) {
                    Log.i(TAG, "is in first page to click transfer app");

                    nodes.get(1).getParent().getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);

                } else if (this.findNodesById(nodes, this.rootNodeInfo, "com.alipay.android.phone.openplatform:id/tab_description")) {
                    Log.i(TAG, "is in tab page, to click first page");
                    if (nodes.size() > 0) {
                        nodes.get(0).getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                }

                return;
            }

            if (isInMineView(nodes)) {
                Log.i(TAG, "is in mine view");
                nodes = this.rootNodeInfo.findAccessibilityNodeInfosByText("账单");
                if (nodes != null && nodes.size() > 0 && firstTimeInMineView == 0) {
                    firstTimeInMineView = 1;
                    firstTimeInBillList = 0;
                    billListRefreshed = 0;
                    nodes.get(0).getParent().getParent().getParent().getParent().getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
            } else if (this.findNodesById(nodes, this.rootNodeInfo, "com.alipay.android.phone.wealth.home:id/sigle_tab_bg")) {
                Log.i(TAG, "is in tab page, to click my page");
                if (nodes.size() > 0) {
                    firstTimeInMineView = 0;
                    nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
            }
        }
    }

    private synchronized void refreshBillList(long ms) {

        Log.i(TAG, "refreshBillList");
        sleep(ms);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float dpi = metrics.densityDpi;
        Log.i(TAG, "dpi=" + dpi);
        Path path = new Path();
        if (320 == dpi) {//720p
            Log.i(TAG, "dpi 320");
            path.moveTo(355, 355);
            path.lineTo(355, 600);
        } else if (480 == dpi) {//1080p
            Log.i(TAG, "dpi 480");
            path.moveTo(533, 533);
            path.lineTo(533, 1000);
        } else { //1440
            Log.i(TAG, "dpi 1440");
            path.moveTo(720, 720);
            path.lineTo(720, 1200);
        }
        GestureDescription.Builder builder = new GestureDescription.Builder();
        GestureDescription gestureDescription = builder.addStroke(new GestureDescription.StrokeDescription(path, 1000, 200L)).build();
        dispatchGesture(gestureDescription, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Log.i(TAG, "refreshBillList onCompleted");
                mMutex = false;
                super.onCompleted(gestureDescription);
                sleep(3000);
                scanBillList();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.i(TAG, "refreshBillList onCancelled");
                mMutex = false;
                super.onCancelled(gestureDescription);
            }
        }, null);

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

        if (!event.getPackageName().toString().contains(AlipayPackageName)) return true;
        // Not a hongbao
        String tip = event.getText().get(0).toString();
        if (!tip.contains(Alipay_NOTIFICATION_TIP) && !tip.contains("成功收款")) return true;
        Log.i(TAG, "valid notification received");
        synchronized (this.notifications) {
            if (this.notificationText != null) {
                this.notifications.add(tip);
                return true;
            }
        }
        this.notificationText = tip;
        this.billListRefreshed = 0;
        this.firstTimeInBillList = 0;
        this.firstTimeInMineView = 0;
        this.messages.clear();
        this.backedFromBusiness = 0;
        this.backedFromChat = 0;
        Parcelable parcelable = event.getParcelableData();
        if (parcelable instanceof Notification) {
            final Notification notification = (Notification) parcelable;
            try {
                /* 清除signature,避免进入会话后误判 */
                signature.cleanSignature();
                this.contentIntent = notification.contentIntent;
                this.powerUtil.handleWakeLock(true);
                //performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
                sleep(500);
                notification.contentIntent.send();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return true;
    }

    @Override
    public void onInterrupt() {

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
        if (this.rootNodeInfo == null) return;

        if (signature.commentString != null) {
            sendComment();
            signature.commentString = null;
        }

        /* 聊天会话窗口，遍历节点匹配“领取红包”和"查看红包" */
        AccessibilityNodeInfo node1 = (sharedPreferences.getBoolean("pref_watch_self", false)) ?
                this.getTheLastNode(WECHAT_VIEW_OTHERS_CH, WECHAT_VIEW_SELF_CH) : this.getTheLastNode(WECHAT_VIEW_OTHERS_CH);
        if (node1 != null &&
                (currentActivityName.contains(WECHAT_LUCKMONEY_CHATTING_ACTIVITY)
                        || currentActivityName.contains(WECHAT_LUCKMONEY_GENERAL_ACTIVITY))) {
            String excludeWords = sharedPreferences.getString("pref_watch_exclude_words", "");
            if (this.signature.generateSignature(node1, excludeWords)) {
                mLuckyMoneyReceived = true;
                mReceiveNode = node1;
                Log.d("sig", this.signature.toString());
            }
            return;
        }

        /* 戳开红包，红包还没抢完，遍历节点匹配“拆红包” */
        AccessibilityNodeInfo node2 = findOpenButton(this.rootNodeInfo);
        Log.d(TAG, "checkNodeInfo  node2 " + node2);
        if (node2 != null && "android.widget.Button".equals(node2.getClassName()) && currentActivityName.contains(WECHAT_LUCKMONEY_RECEIVE_ACTIVITY)
                && (mUnpackNode == null || mUnpackNode != null && !mUnpackNode.equals(node2))) {
            mUnpackNode = node2;
            mUnpackCount += 1;
            return;
        }

        /* 戳开红包，红包已被抢完，遍历节点匹配“红包详情”和“手慢了” */
        boolean hasNodes = this.hasOneOfThoseNodes(
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
        sleep(ms);
        Log.i(TAG, "press home");
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    private void back(long ms) {
        sleep(ms);
        Log.i(TAG, "press back");
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    private void saveNotification(String amount, String reference, String name, String mobile) {
        String msg = String.format("%s|#|%s|#|%s|#|%s", amount, reference, name, mobile);
        Log.i(TAG, "saving notification:" + msg);
        messages.add(msg);
    }


    private void sendAllNotification() {
        for (int i = 0; i < messages.size(); ++i) {
            String msg = messages.get(i);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                    .setContentTitle("支付宝转账监控")
                    .setSmallIcon(R.mipmap.icon_alipay)
                    .setContentText(msg)
                    .setContentIntent(this.contentIntent);

            final Notification notification = builder.build();
            //notification.flags = Notification.FLAG_ONGOING_EVENT;

            final int nid = this.nid++;
            //int delayFlag = sharedPreferences.getInt("pref_open_delay", 0) * 1000;

            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

            notificationManager.notify(nid, notification);

            sleep(500);
        }

        messages.clear();
        this.notificationText = null;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Exception ex) {

        }
    }

    private void sendNotification(String amount, String reference, String name, String mobile) {
        Log.i(TAG, "sending notification");
        if ("".equals(amount + reference + name + mobile)) {
            Log.i(TAG, "skip sending noti");
            return;
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setContentTitle("支付宝转账监控")
                .setSmallIcon(R.mipmap.icon_alipay)
                .setContentText(amount + ":" + reference + ":" + name + ":" + mobile)
                .setContentIntent(this.contentIntent);

        this.notificationText = null;
        final Notification notification = builder.build();
        //notification.flags = Notification.FLAG_ONGOING_EVENT;

        final int nid = this.nid++;
        this.notificationText = null;
        //int delayFlag = sharedPreferences.getInt("pref_open_delay", 0) * 1000;

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        notificationManager.notify(nid, notification);
    }

    private void checkNodeInfo(int eventType) {
        if (this.rootNodeInfo == null) return;

        AccessibilityNodeInfo node1 = this.getTheLastNode("com.alipay.mobile.chatapp:id/biz_desc");
        if (node1 != null) {
            String text = node1.getText().toString();
            text = text.substring(0, text.length() - 1);
            try {
                float amount = Float.parseFloat(text);
                Log.d(TAG, "收到钱数:" + amount);

                node1 = this.getTheLastNode("com.alipay.mobile.chatapp:id/biz_title");
                String title = node1.getText().toString();
                this.sendNotification(text, "", title, "");
                performGlobalAction(GLOBAL_ACTION_HOME);
                this.powerUtil.handleWakeLock(false);
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            return;
        }

        /* 戳开红包，红包还没抢完，遍历节点匹配“拆红包” */
        //AccessibilityNodeInfo node2 = findOpenButton(this.rootNodeInfo);

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

            nodes = this.rootNodeInfo.findAccessibilityNodeInfosByText(text);

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

            nodes = this.rootNodeInfo.findAccessibilityNodeInfosByViewId(text);//biz_title;//.findAccessibilityNodeInfosByText(text)

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
        Log.i(TAG, "service connected");
        super.onServiceConnected();
        this.watchFlagsFromPreference();
        registerClipEvents();
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
                            Log.i(TAG, "copied text: " + text);

                            if (payInfo == null) {

                                JSONObject json = new JSONObject(text.toString());
                                payInfo = json;
                                if (!payInfo.optString("type").equals("wechat")) {
                                    Intent intent = getApplicationContext().getPackageManager().getLaunchIntentForPackage("com.eg.android.AlipayGphone");
                                    //intent.setClassName("com.eg.android.AlipayGphone", "com.alipay.mobile.transferapp.ui.TransferToCardFormActivity_");
                                    intent.setAction("android.intent.action.MAIN");
                                    intent.addCategory(Intent.CATEGORY_LAUNCHER);
                                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                                    //intent.putExtra("data", "在此处添加数据信息");
                                    startActivity(intent);
                                }

                            }
                        }
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });
    }

    private void showReminder() {
        Log.i(TAG, "show reminder");
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

        this.powerUtil = new PowerUtil(this);
        Boolean watchOnLockFlag = sharedPreferences.getBoolean("pref_watch_on_lock", false);
        this.powerUtil.handleWakeLock(watchOnLockFlag);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equals("pref_watch_on_lock")) {
            Boolean changedValue = sharedPreferences.getBoolean(key, false);
            this.powerUtil.handleWakeLock(changedValue);
            if (changedValue == true) {
                this.notificationText = "begin test";
            } else {
            }

            backedFromChat = 0;
            backedFromBusiness = 0;
            firstTimeInMineView = 0;
        }
    }

    @Override
    public void onDestroy() {
        this.powerUtil.handleWakeLock(false);
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