// QueueLess Service Worker for Web Push & Offline Support

self.addEventListener('install', (event) => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(clients.claim());
});

self.addEventListener('push', (event) => {
  if (!event.data) {
    return;
  }

  try {
    const payload = event.data.json();
    const tag = payload.tag || ('queueless-queue-' + (payload.trackingToken || 'default'));

    // If terminal state action is CLOSE, dismiss active notification with this tag
    if (payload.action === 'CLOSE') {
      event.waitUntil(
        self.registration.getNotifications({ tag }).then((notifications) => {
          notifications.forEach((n) => n.close());
        })
      );
      return;
    }

    const title = payload.title || 'QueueLess Update';
    const options = {
      body: payload.body || 'Your queue position has changed.',
      icon: payload.icon || '/favicon.svg',
      badge: payload.badge || '/favicon.svg',
      tag: tag,
      renotify: true,
      data: {
        url: payload.url || (payload.trackingToken ? `/track/${payload.trackingToken}` : '/'),
        trackingToken: payload.trackingToken,
        status: payload.status,
      },
      vibrate: [200, 100, 200],
    };

    event.waitUntil(self.registration.showNotification(title, options));
  } catch (err) {
    console.error('Error handling push notification payload:', err);
  }
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();

  const targetUrl = event.notification.data?.url || '/';

  event.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clientList) => {
      // If customer tracking window is already open, focus it
      for (const client of clientList) {
        if (client.url.includes(targetUrl) && 'focus' in client) {
          return client.focus();
        }
      }
      // Otherwise open the exact private tracking URL
      if (clients.openWindow) {
        return clients.openWindow(targetUrl);
      }
    })
  );
});
