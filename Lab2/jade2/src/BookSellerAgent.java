package jadelab2;

import jade.core.Agent;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

import java.util.*;

public class BookSellerAgent extends Agent {
  // Mapiramo naslov knjige na Listu ponuda (svaka ponuda je int[]{price, shipping})
  private Hashtable<String, List<int[]>> catalogue;
  private BookSellerGui myGui;

  protected void setup() {
    catalogue = new Hashtable<String, List<int[]>>();
    myGui = new BookSellerGui(this);
    myGui.display();

    DFAgentDescription dfd = new DFAgentDescription();
    dfd.setName(getAID());
    ServiceDescription sd = new ServiceDescription();
    sd.setType("book-selling");
    sd.setName("JADE-book-trading");
    dfd.addServices(sd);
    try {
      DFService.register(this, dfd);
    } catch (FIPAException fe) {
      fe.printStackTrace();
    }
    
    addBehaviour(new OfferRequestsServer());
    addBehaviour(new PurchaseOrdersServer());
  }

  protected void takeDown() {
    try {
      DFService.deregister(this);
    } catch (FIPAException fe) {
      fe.printStackTrace();
    }
    myGui.dispose();
    System.out.println("Seller agent " + getAID().getName() + " terminated.");
  }

  public void updateCatalogue(final String title, final int price, final int shipping) {
    addBehaviour(new OneShotBehaviour() {
      public void action() {
        String cleanTitle = title.trim();
        List<int[]> offers = catalogue.get(cleanTitle);
        if (offers == null) {
          offers = new ArrayList<int[]>();
          catalogue.put(cleanTitle, offers);
        }
        // Dodajemo novu ponudu u listu ponuda za tu knjigu
        offers.add(new int[]{price, shipping});
        System.out.println(getAID().getLocalName() + ": " + cleanTitle + " put into catalogue. Price = " + price + ", Shipping = " + shipping);
      }
    });
  }
  
  private class OfferRequestsServer extends CyclicBehaviour {
    public void action() {
      MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CFP);
      ACLMessage msg = myAgent.receive(mt);
      if (msg != null) {
        String title = msg.getContent().trim();
        ACLMessage reply = msg.createReply();
        
        List<int[]> offers = catalogue.get(title);
        
        if (offers != null && !offers.isEmpty()) {
          // Pronađi ponudu s NAJMANJOM ukupnom cijenom (cijena + dostava)
          int[] bestOffer = offers.get(0);
          int bestTotal = bestOffer[0] + bestOffer[1];

          for (int[] offer : offers) {
            int currentTotal = offer[0] + offer[1];
            if (currentTotal < bestTotal) {
              bestTotal = currentTotal;
              bestOffer = offer;
            }
          }

          reply.setPerformative(ACLMessage.PROPOSE);
          reply.setContent(bestOffer[0] + "," + bestOffer[1]);
        } else {
          reply.setPerformative(ACLMessage.REFUSE);
          reply.setContent("not-available");
        }
        myAgent.send(reply);
      } else {
        block();
      }
    }
  }

  private class PurchaseOrdersServer extends CyclicBehaviour {
    public void action() {
      MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL);
      ACLMessage msg = myAgent.receive(mt);
      if (msg != null) {
        String title = msg.getContent().trim();
        ACLMessage reply = msg.createReply();
        
        List<int[]> offers = catalogue.get(title);
        
        if (offers != null && !offers.isEmpty()) {
          // Pri kupnji uklanjamo najjeftiniju ponudu koja je bila ponuđena kupcu
          int bestIndex = 0;
          int bestTotal = offers.get(0)[0] + offers.get(0)[1];

          for (int i = 1; i < offers.size(); i++) {
            int currentTotal = offers.get(i)[0] + offers.get(i)[1];
            if (currentTotal < bestTotal) {
              bestTotal = currentTotal;
              bestIndex = i;
            }
          }

          offers.remove(bestIndex);
          if (offers.isEmpty()) {
            catalogue.remove(title);
          }

          reply.setPerformative(ACLMessage.INFORM);
          System.out.println(getAID().getLocalName() + ": " + title + " sold to " + msg.getSender().getLocalName());
        } else {
          reply.setPerformative(ACLMessage.FAILURE);
          reply.setContent("not-available");
        }
        myAgent.send(reply);
      } else {
        block();
      }
    }
  }
}