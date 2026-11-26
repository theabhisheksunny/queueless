// QueueLess Service Worker for Web Push & Offline Support

self.addEventListener('install', () => {
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
    const newTimestamp = payload.timestamp || Date.now();

    const showPromise = self.registration.getNotifications({ tag }).then((existingNotifications) => {
      // Out-of-order check: If an existing notification has a newer timestamp, do not overwrite with stale notification
      if (existingNotifications && existingNotifications.length > 0) {
        for (const existing of existingNotifications) {
          if (existing.data && existing.data.timestamp && existing.data.timestamp > newTimestamp) {
            // Newer notification already displayed
            return;
          }
        }
      }

      const options = {
        body: payload.body || 'Your queue position has changed.',
        icon: payload.icon || '/favicon.svg',
        badge: payload.badge || '/favicon.svg',
        tag: tag,
        renotify: true,
        data: {
          url: payload.trackingToken ? `/track/${encodeURIComponent(payload.trackingToken)}` : '/',
          trackingToken: payload.trackingToken,
          status: payload.status,
          timestamp: newTimestamp,
        },
        vibrate: [200, 100, 200],
      };

      return self.registration.showNotification(title, options);
    });

    event.waitUntil(showPromise);
  } catch (err) {
    console.error('Error handling push notification payload:', err);
  }
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();

  const rawUrl = event.notification.data?.url || '/';
  const trackingToken = event.notification.data?.trackingToken;

  // Strictly sanitize destination: only allow same-origin relative URLs starting with /track/ or /
  let safePath = '/';
  if (trackingToken && /^[a-zA-Z0-9_-]+$/.test(trackingToken)) {
    safePath = `/track/${encodeURIComponent(trackingToken)}`;
  } else if (typeof rawUrl === 'string' && rawUrl.startsWith('/') && !rawUrl.startsWith('//') && !rawUrl.includes('\\')) {
    safePath = rawUrl;
  }

  // Construct absolute URL strictly bounded to self.location.origin
  const targetUrl = new URL(safePath, self.location.origin).href;

  event.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clientList) => {
      // If customer tracking window is already open, focus it
      for (const client of clientList) {
        if ((client.url === targetUrl || client.url.startsWith(targetUrl + '?')) && 'focus' in client) {
          return client.focus();
        }
      }
      // Otherwise open the exact private tracking URL on our origin
      if (clients.openWindow) {
        return clients.openWindow(targetUrl);
      }
    })
  );
});
