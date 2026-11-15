export type Language = 'en' | 'hi';

export const translations = {
  en: {
    // Brand
    brandName: 'QueueLess',
    tagline: 'Simple QR-Based Digital Token Register',
    
    // Auth
    signIn: 'Sign In',
    signUp: 'Create Account',
    loginTitle: 'Shop Owner Login',
    registerTitle: 'Register Your Shop',
    email: 'Email Address',
    password: 'Password',
    fullName: 'Your Name',
    phone: 'Phone Number (Optional)',
    shopName: 'Shop Name',
    category: 'Business Category',
    avgServiceTime: 'Avg. Service Time per Customer',
    minutes: 'minutes',
    loginBtn: 'Log In to Dashboard',
    registerBtn: 'Create Shop & Start Queue',
    demoLoginBtn: '⚡ Quick Demo Login (owner@queueless.com)',
    noAccount: "Don't have an account?",
    haveAccount: 'Already have an account?',
    logout: 'Logout',
    
    // Dashboard Stats
    queueStatus: 'Queue Status',
    open: 'OPEN',
    closed: 'CLOSED',
    startQueue: 'Start Queue',
    closeQueue: 'Close Queue',
    confirmCloseQueue: 'Are you sure you want to close the queue? New customers will not be able to join.',
    totalToday: 'Total Issued Today',
    waitingCustomers: 'Waiting Customers',
    currentlyServing: 'Currently Serving',
    noneServing: 'No customer serving',
    
    // Actions
    addCustomer: 'Add Customer',
    showQrCode: 'Show QR Code',
    callNext: 'Call Next Customer',
    noWaitingToCall: 'No waiting customers in queue',
    
    // Tabs & Filters
    tabAll: 'All Active',
    tabWaiting: 'Waiting',
    tabServing: 'Serving',
    tabCompleted: 'Completed / History',
    
    // Table / Card Columns
    token: 'Token',
    customer: 'Customer',
    joinedAt: 'Joined At',
    status: 'Status',
    actions: 'Actions',
    
    // Queue Statuses
    WAITING: 'WAITING',
    CALLED: 'CALLED',
    SERVING: 'SERVING',
    COMPLETED: 'COMPLETED',
    SKIPPED: 'SKIPPED',
    NO_SHOW: 'NO SHOW',
    LEFT: 'LEFT',
    CANCELLED: 'REMOVED',
    
    // Customer Actions
    btnCall: 'Call',
    btnServe: 'Start Serving',
    btnComplete: 'Complete',
    btnSkip: 'Skip',
    btnRecall: 'Recall',
    btnNoShow: 'No-Show',
    btnEdit: 'Edit',
    btnRemove: 'Remove',
    
    // Customer Modal
    modalAddTitle: 'Add Customer Manually',
    modalEditTitle: 'Edit Customer Details',
    customerNameLabel: 'Customer Name',
    customerPhoneLabel: 'Mobile Number (Optional)',
    enterNamePlaceholder: 'e.g. Rahul Sharma',
    enterPhonePlaceholder: 'e.g. 9876543210',
    cancel: 'Cancel',
    saveChanges: 'Save Changes',
    generateTokenBtn: 'Generate Token',
    
    // QR Modal
    qrTitle: 'Scan to Join Queue',
    qrSubtitle: 'Customers can scan this QR code with any smartphone camera to get a digital token.',
    printPoster: 'Print Counter Poster',
    downloadQr: 'Download QR Code',
    copyJoinLink: 'Copy Join Link',
    linkCopied: 'Link copied to clipboard!',
    openCustomerView: 'Open Customer View',
    
    // Customer Join Page
    joinHeading: 'Get Your Token',
    joinSubheading: 'Scan done! Enter your name below to join the digital queue.',
    getMyToken: 'Get My Token',
    queueClosedNotice: 'Queue is currently closed by the shop owner. Please check back later or inquire at the counter.',
    
    // Customer Track Page
    yourToken: 'Your Token Number',
    peopleAhead: 'People ahead of you',
    estWaitTime: 'Approximate wait time',
    approxMinutes: 'mins',
    waitDisclaimer: 'Waiting time is an approximate estimate based on average service time.',
    turnAlertHeading: "🔔 It's Your Turn!",
    turnAlertMsg: 'Please proceed to the service counter immediately.',
    servingAlertHeading: '✂️ You are being served now',
    waitingAlertMsg: 'Please relax in the waiting area. We will call your token shortly.',
    leaveQueueBtn: 'Leave Queue',
    confirmLeaveQueue: 'Are you sure you want to leave the queue? You will lose your token position.',
    rejoinQueueBtn: 'Rejoin Queue',
    liveUpdatesActive: 'Live Updates Connected',
    reconnecting: 'Reconnecting...',
    
    // Subscription
    subscription: 'Subscription',
    subActive: 'Subscription Active',
    subExpired: 'Subscription Expired',
    subExpiresIn: 'Expires in',
    days: 'days',
    renewSub: 'Renew Subscription',
    planMonthlyTitle: 'Monthly Plan',
    planMonthlyPrice: '₹51',
    planMonthlyDuration: 'for 30 days',
    planYearlyTitle: 'Yearly Plan',
    planYearlyPrice: '₹501',
    planYearlyDuration: 'for 365 days',
    payViaUpi: 'Pay Directly via UPI',
    copyUpiId: 'Copy UPI ID',
    copied: 'Copied!',
    upiInstructionsHeading: 'How to pay:',
    step1: 'Open any UPI App (GPay, PhonePe, Paytm, BHIM).',
    step2: 'Scan the UPI QR code or transfer to UPI ID.',
    step3: 'Enter your shop mobile number in payment remarks.',
    step4: 'Copy the 12-digit UPI Reference / UTR Number and submit below for manual activation.',
    enterUtrLabel: 'Enter 12-digit UPI Reference ID (UTR)',
    submitUtrBtn: 'Submit Payment Reference',
    utrSubmittedNotice: 'UTR submitted! Platform admin will verify payment and activate your subscription.',
    
    // Settings
    settings: 'Shop Settings',
    shopProfile: 'Shop Profile',
    saveSettings: 'Save Settings',
    settingsUpdated: 'Settings updated successfully.',
    
    // Admin
    adminTitle: 'Minimal Admin Control',
    adminKeyLabel: 'Administrator Secret Key',
    adminKeyPlaceholder: 'Enter admin key (default: admin-secret-queueless-2026)',
    adminVerifyBtn: 'Access Admin Panel',
    configUpiHeading: 'Configure Subscription UPI ID',
    merchantName: 'Merchant / Payee Name',
    updateConfigBtn: 'Save UPI Config',
    manageShopsHeading: 'Registered Shops & Subscriptions',
    shopOwner: 'Owner',
    subStatus: 'Sub Status',
    expiresOn: 'Expires On',
    activateMonthBtn: '+30 Days (₹51)',
    activateYearBtn: '+365 Days (₹501)',
  },

  hi: {
    // Brand
    brandName: 'QueueLess (क्यूलेस)',
    tagline: 'सरल क्यूआर डिजिटल टोकन रजिस्टर',
    
    // Auth
    signIn: 'साइन इन करें',
    signUp: 'खाता बनाएं',
    loginTitle: 'दुकानदार लॉगिन',
    registerTitle: 'अपनी दुकान रजिस्टर करें',
    email: 'ईमेल पता',
    password: 'पासवर्ड',
    fullName: 'आपका नाम',
    phone: 'मोबाइल नंबर (वैकल्पिक)',
    shopName: 'दुकान का नाम',
    category: 'व्यापार श्रेणी',
    avgServiceTime: 'प्रति ग्राहक औसत समय',
    minutes: 'मिनट',
    loginBtn: 'डैशबोर्ड में लॉगिन करें',
    registerBtn: 'दुकान बनाएं और कतार शुरू करें',
    demoLoginBtn: '⚡ डेमो लॉगिन (owner@queueless.com)',
    noAccount: 'क्या आपका खाता नहीं है?',
    haveAccount: 'पहले से खाता है?',
    logout: 'लॉगआउट',
    
    // Dashboard Stats
    queueStatus: 'कतार की स्थिति',
    open: 'चालू (OPEN)',
    closed: 'बंद (CLOSED)',
    startQueue: 'कतार शुरू करें',
    closeQueue: 'कतार बंद करें',
    confirmCloseQueue: 'क्या आप कतार बंद करना चाहते हैं? नए ग्राहक शामिल नहीं हो सकेंगे।',
    totalToday: 'आज जारी कुल टोकन',
    waitingCustomers: 'प्रतीक्षारत ग्राहक',
    currentlyServing: 'वर्तमान में सेवारत',
    noneServing: 'कोई ग्राहक नहीं',
    
    // Actions
    addCustomer: 'ग्राहक जोड़ें',
    showQrCode: 'QR कोड दिखाएं',
    callNext: 'अगले ग्राहक को बुलाएं',
    noWaitingToCall: 'कतार में कोई प्रतीक्षारत ग्राहक नहीं है',
    
    // Tabs & Filters
    tabAll: 'सभी सक्रिय',
    tabWaiting: 'प्रतीक्षारत (Waiting)',
    tabServing: 'सेवारत (Serving)',
    tabCompleted: 'पूर्ण / इतिहास',
    
    // Table / Card Columns
    token: 'टोकन',
    customer: 'ग्राहक का नाम',
    joinedAt: 'शामिल होने का समय',
    status: 'स्थिति',
    actions: 'कार्रवाई',
    
    // Queue Statuses
    WAITING: 'प्रतीक्षारत (WAITING)',
    CALLED: 'बुलाया गया (CALLED)',
    SERVING: 'सेवा जारी (SERVING)',
    COMPLETED: 'पूर्ण (COMPLETED)',
    SKIPPED: 'छोड़ा गया (SKIPPED)',
    NO_SHOW: 'अनुपस्थित (NO SHOW)',
    LEFT: 'चला गया (LEFT)',
    CANCELLED: 'हटाया गया',
    
    // Customer Actions
    btnCall: 'बुलाएं',
    btnServe: 'सेवा शुरू करें',
    btnComplete: 'पूर्ण करें',
    btnSkip: 'स्किप करें',
    btnRecall: 'पुनः बुलाएं',
    btnNoShow: 'अनुपस्थित',
    btnEdit: 'संपादित करें',
    btnRemove: 'हटाएं',
    
    // Customer Modal
    modalAddTitle: 'ग्राहक को मैन्युअल रूप से जोड़ें',
    modalEditTitle: 'ग्राहक विवरण बदलें',
    customerNameLabel: 'ग्राहक का नाम',
    customerPhoneLabel: 'मोबाइल नंबर (वैकल्पिक)',
    enterNamePlaceholder: 'उदा. राहुल शर्मा',
    enterPhonePlaceholder: 'उदा. 9876543210',
    cancel: 'रद्द करें',
    saveChanges: 'सहेजें',
    generateTokenBtn: 'टोकन जनरेट करें',
    
    // QR Modal
    qrTitle: 'कतार में शामिल होने के लिए स्कैन करें',
    qrSubtitle: 'ग्राहक डिजिटल टोकन प्राप्त करने के लिए किसी भी स्मार्टफोन कैमरे से इस QR कोड को स्कैन कर सकते हैं।',
    printPoster: 'काउंटर पोस्टर प्रिंट करें',
    downloadQr: 'QR कोड डाउनलोड करें',
    copyJoinLink: 'लिंक कॉपी करें',
    linkCopied: 'लिंक कॉपी हो गया!',
    openCustomerView: 'ग्राहक दृश्य खोलें',
    
    // Customer Join Page
    joinHeading: 'अपना टोकन प्राप्त करें',
    joinSubheading: 'स्कैन सफल! कतार में शामिल होने के लिए अपना नाम नीचे दर्ज करें।',
    getMyToken: 'टोकन प्राप्त करें',
    queueClosedNotice: 'कतार वर्तमान में दुकानदार द्वारा बंद है। कृपया बाद में प्रयास करें।',
    
    // Customer Track Page
    yourToken: 'आपका टोकन नंबर',
    peopleAhead: 'आपसे आगे ग्राहक',
    estWaitTime: 'अनुमानित प्रतीक्षा समय',
    approxMinutes: 'मिनट',
    waitDisclaimer: 'प्रतीक्षा समय औसत सेवा समय पर आधारित एक अनुमान है।',
    turnAlertHeading: '🔔 आपकी बारी आ गई!',
    turnAlertMsg: 'कृपया तुरंत काउंटर पर जाएं।',
    servingAlertHeading: '✂️ आपकी सेवा चालू है',
    waitingAlertMsg: 'कृपया प्रतीक्षा क्षेत्र में बैठें। हम जल्द ही आपका टोकन बुलाएंगे।',
    leaveQueueBtn: 'कतार छोड़ें',
    confirmLeaveQueue: 'क्या आप कतार छोड़ना चाहते हैं? आप अपना टोकन स्थान खो देंगे।',
    rejoinQueueBtn: 'पुनः शामिल हों',
    liveUpdatesActive: 'लाइव अपडेट सक्रिय',
    reconnecting: 'पुनः कनेक्ट हो रहा है...',
    
    // Subscription
    subscription: 'सदस्यता (Subscription)',
    subActive: 'सदस्यता सक्रिय है',
    subExpired: 'सदस्यता समाप्त हो गई',
    subExpiresIn: 'शेष दिन:',
    days: 'दिन',
    renewSub: 'सदस्यता नवीनीकृत करें',
    planMonthlyTitle: 'मासिक योजना',
    planMonthlyPrice: '₹51',
    planMonthlyDuration: '30 दिनों के लिए',
    planYearlyTitle: 'वार्षिक योजना',
    planYearlyPrice: '₹501',
    planYearlyDuration: '365 दिनों के लिए',
    payViaUpi: 'सीधे UPI द्वारा भुगतान करें',
    copyUpiId: 'UPI ID कॉपी करें',
    copied: 'कॉपी हो गया!',
    upiInstructionsHeading: 'भुगतान कैसे करें:',
    step1: 'कोई भी UPI ऐप (GPay, PhonePe, Paytm, BHIM) खोलें।',
    step2: 'QR कोड स्कैन करें या UPI ID पर राशि भेजें।',
    step3: 'रिमार्क्स में अपनी दुकान का मोबाइल नंबर लिखें।',
    step4: '12-अंकों का UPI संदर्भ / UTR नंबर कॉपी करके नीचे दर्ज करें।',
    enterUtrLabel: '12-अंकों का UPI संदर्भ आईडी (UTR) दर्ज करें',
    submitUtrBtn: 'भुगतान संदर्भ जमा करें',
    utrSubmittedNotice: 'UTR जमा हो गया! व्यवस्थापक सत्यापन के बाद सक्रिय कर देगा।',
    
    // Settings
    settings: 'दुकान सेटिंग्स',
    shopProfile: 'दुकान विवरण',
    saveSettings: 'सेटिंग्स सहेजें',
    settingsUpdated: 'सेटिंग्स सफलतापूर्वक अपडेट की गईं।',
    
    // Admin
    adminTitle: 'व्यवस्थापक नियंत्रण',
    adminKeyLabel: 'व्यवस्थापक गुप्त कुंजी',
    adminKeyPlaceholder: 'व्यवस्थापक कुंजी दर्ज करें',
    adminVerifyBtn: 'प्रवेश करें',
    configUpiHeading: 'सदस्यता UPI ID कॉन्फ़िगर करें',
    merchantName: 'व्यापारी / प्राप्तकर्ता नाम',
    updateConfigBtn: 'सेव करें',
    manageShopsHeading: 'पंजीकृत दुकानें और सदस्यता',
    shopOwner: 'मालिक',
    subStatus: 'सदस्यता स्थिति',
    expiresOn: 'समाप्ति तिथि',
    activateMonthBtn: '+30 दिन (₹51)',
    activateYearBtn: '+365 दिन (₹501)',
  }
};
