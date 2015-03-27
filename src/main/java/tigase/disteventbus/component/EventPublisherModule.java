package tigase.disteventbus.component;

import java.util.Collection;
import java.util.logging.Level;

import tigase.component.exceptions.ComponentException;
import tigase.criteria.Criteria;
import tigase.disteventbus.EventHandler;
import tigase.disteventbus.component.stores.Subscription;
import tigase.disteventbus.component.stores.SubscriptionStore;
import tigase.disteventbus.impl.LocalEventBus;
import tigase.disteventbus.impl.LocalEventBus.LocalEventBusListener;
import tigase.kernel.beans.Initializable;
import tigase.kernel.beans.Inject;
import tigase.kernel.beans.UnregisterAware;
import tigase.server.Packet;
import tigase.server.Permissions;
import tigase.util.TigaseStringprepException;
import tigase.xml.Element;
import tigase.xmpp.JID;

public class EventPublisherModule extends AbstractEventBusModule implements Initializable, UnregisterAware {

	public final static String ID = "publisher";

	@Inject
	private EventBusComponent component;

	private final LocalEventBusListener eventBusListener = new LocalEventBusListener() {

		@Override
		public void onAddHandler(String name, String xmlns, EventHandler handler) {
		}

		@Override
		public void onFire(String name, String xmlns, Element event) {
			publishEvent(name, xmlns, event);
		}

		@Override
		public void onRemoveHandler(String name, String xmlns, EventHandler handler) {
		}
	};

	@Inject(bean = "localEventBus")
	private LocalEventBus localEventBus;

	@Inject
	private SubscriptionStore subscriptionStore;

	@Override
	public void beforeUnregister() {
		localEventBus.removeListener(eventBusListener);
	}

	public EventBusComponent getComponent() {
		return component;
	}

	@Override
	public String[] getFeatures() {
		return null;
	}

	public LocalEventBus getLocalEventBus() {
		return localEventBus;
	}

	@Override
	public Criteria getModuleCriteria() {
		return null;
	}

	public SubscriptionStore getSubscriptionStore() {
		return subscriptionStore;
	}

	@Override
	public void initialize() {
		localEventBus.addListener(eventBusListener);
	}

	@Override
	public void process(Packet packet) throws ComponentException, TigaseStringprepException {
	}

	public void publishEvent(Element event) {
		publishEvent(event.getName(), event.getXMLNS(), event);
	}

	private void publishEvent(Element pubsubEventElem, String from, JID toJID) throws TigaseStringprepException {
		Packet message = Packet.packetInstance(new Element("message", new String[] { "to", "from", "id" }, new String[] {
				toJID.toString(), from, nextStanzaID() }));
		message.getElement().addChild(pubsubEventElem);
		message.setXMLNS(Packet.CLIENT_XMLNS);

		message.setPermissions(Permissions.ADMIN);

		write(message);
	}

	public void publishEvent(String name, String xmlns, Element event) {
		final Collection<Subscription> subscribers = subscriptionStore.getSubscribersJIDs(name, xmlns);
		publishEvent(name, xmlns, event, subscribers);
	}

	public void publishEvent(String name, String xmlns, Element event, Collection<Subscription> subscribers) {
		try {
			final Element eventElem = new Element("event", new String[] { "xmlns" },
					new String[] { "http://jabber.org/protocol/pubsub#event" });
			final Element itemsElem = new Element("items", new String[] { "node" }, new String[] { NodeNameUtil.createNodeName(
					name, xmlns) });
			eventElem.addChild(itemsElem);
			final Element itemElem = new Element("item");
			itemElem.addChild(event);
			itemsElem.addChild(itemElem);

			if (log.isLoggable(Level.FINER))
				log.finer("Sending event (" + name + ", " + xmlns + ") to " + subscribers);

			for (Subscription subscriber : subscribers) {

				String from;
				if (subscriber.getServiceJID() == null) {
					from = component.getComponentId().toString();
				} else {
					from = subscriber.getServiceJID().toString();
				}
				JID toJID = subscriber.getJid();

				publishEvent(eventElem, from, toJID);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void setComponent(EventBusComponent component) {
		this.component = component;
	}

	public void setLocalEventBus(LocalEventBus localEventBus) {
		this.localEventBus = localEventBus;
	}

	public void setSubscriptionStore(SubscriptionStore subscriptionStore) {
		this.subscriptionStore = subscriptionStore;
	}

}
