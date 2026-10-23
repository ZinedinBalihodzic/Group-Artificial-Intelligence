package jadelab2;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

public class BookBuyerAgent extends Agent {
  private BookBuyerGui myGui;
  private String targetBookTitle;
  
  private int budget = 100;
  private AID[] sellerAgents;
  
  protected void setup() {
    targetBookTitle = "";
    int interval = 20000;
    Object[] args = getArguments();
    if (args != null && args.length > 0) {
      interval = Integer.parseInt(args[0].toString());
      if (args.length > 1) {
        budget = Integer.parseInt(args[1].toString());
      }
    }
    
    System.out.println("Hello! " + getAID().getLocalName() + " is ready for purchase orders. Initial budget: $" + budget);
    myGui = new BookBuyerGui(this);
    myGui.display();

    addBehaviour(new TickerBehaviour(this, interval) {
      protected void onTick() {
        if (!targetBookTitle.equals("")) {
          System.out.println(getAID().getLocalName() + ": I'm looking for " + targetBookTitle + " (Current budget: $" + budget + ")");
          
          DFAgentDescription template = new DFAgentDescription();
          ServiceDescription sd = new ServiceDescription();
          sd.setType("book-selling");
          template.addServices(sd);
          try {
            DFAgentDescription[] result = DFService.search(myAgent, template);
            System.out.println(getAID().getLocalName() + ": the following sellers have been found:");
            sellerAgents = new AID[result.length];
            for (int i = 0; i < result.length; ++i) {
              sellerAgents[i] = result[i].getName();
              System.out.println(sellerAgents[i].getLocalName());
            }
          } catch (FIPAException fe) {
            fe.printStackTrace();
          }

          myAgent.addBehaviour(new RequestPerformer());
        }
      }
    });
  }

  public void lookForTitle(final String title) {
    addBehaviour(new OneShotBehaviour() {
      public void action() {
        targetBookTitle = title;
        System.out.println(getAID().getLocalName() + ": purchase order for " + targetBookTitle + " accepted");
      }
    });
  }

  protected void takeDown() {
    myGui.dispose();
    System.out.println("Buyer agent " + getAID().getLocalName() + " terminated.");
  }
  
  private class RequestPerformer extends Behaviour {
    private AID bestSeller;
    private int bestTotalPrice;
    private int repliesCnt = 0;
    private MessageTemplate mt;
    private int step = 0;

    private long startCFPTime;
    private final long TIMEOUT_MS = 5000;
    
    private String failureReason = "Book not available on sale.";

    public void action() {
      switch (step) {
      case 0:
        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
        for (int i = 0; i < sellerAgents.length; ++i) {
          cfp.addReceiver(sellerAgents[i]);
        } 
        cfp.setContent(targetBookTitle);
        cfp.setConversationId("book-trade");
        cfp.setReplyWith("cfp" + System.currentTimeMillis());
        myAgent.send(cfp);
        
        mt = MessageTemplate.and(MessageTemplate.MatchConversationId("book-trade"),
                                 MessageTemplate.MatchInReplyTo(cfp.getReplyWith()));
        
        startCFPTime = System.currentTimeMillis();
        step = 1;
        break;

      case 1:
        ACLMessage reply = myAgent.receive(mt);
        if (reply != null) {
          if (reply.getPerformative() == ACLMessage.PROPOSE) {
            String[] parts = reply.getContent().split(",");
            int bookPrice = Integer.parseInt(parts[0]);
            int shippingCost = Integer.parseInt(parts[1]);
            int totalPrice = bookPrice + shippingCost;

            System.out.println(getAID().getLocalName() + ": Received proposal from " 
                + reply.getSender().getLocalName() + " (Book: $" + bookPrice 
                + ", Shipping: $" + shippingCost + ", Total: $" + totalPrice + ")");

            if (totalPrice <= budget) {
              if (bestSeller == null || totalPrice < bestTotalPrice) {
                bestTotalPrice = totalPrice;
                bestSeller = reply.getSender();
              }
            } else {
              if (bestSeller == null) {
                failureReason = "Exceeds available budget (Required: $" + totalPrice + ", Available: $" + budget + ").";
              }
              System.out.println(getAID().getLocalName() + ": Rejected offer from " 
                + reply.getSender().getLocalName() + " (Total: $" + totalPrice + " exceeds budget: $" + budget + ")");
            }
          }
          
          repliesCnt++;
          if (repliesCnt >= sellerAgents.length) {
            step = 2;
          }
        } else {
          if (System.currentTimeMillis() - startCFPTime > TIMEOUT_MS) {
            System.out.println(getAID().getLocalName() + ": Timeout reached! Proceeding with collected proposals.");
            if (repliesCnt == 0 && bestSeller == null) {
              failureReason = "No sellers responded to CFP (Timeout).";
            }
            step = 2;
          } else {
            long remainingTime = TIMEOUT_MS - (System.currentTimeMillis() - startCFPTime);
            block(remainingTime > 0 ? remainingTime : 1);
          }
        }
        break;

      case 2:
        if (bestSeller != null) {
          ACLMessage order = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
          order.addReceiver(bestSeller);
          order.setContent(targetBookTitle);
          order.setConversationId("book-trade");
          order.setReplyWith("order" + System.currentTimeMillis());
          myAgent.send(order);
          mt = MessageTemplate.and(MessageTemplate.MatchConversationId("book-trade"),
                                   MessageTemplate.MatchInReplyTo(order.getReplyWith()));
          step = 3;
        } else {
          step = 4;
        }
        break;

      case 3:      
        reply = myAgent.receive(mt);
        if (reply != null) {
          if (reply.getPerformative() == ACLMessage.INFORM) {
            budget -= bestTotalPrice;

            System.out.println(getAID().getLocalName() + ": " + targetBookTitle 
                + " purchased for total price of $" + bestTotalPrice 
                + " from " + reply.getSender().getLocalName());
            System.out.println(getAID().getLocalName() + ": Remaining budget: $" + budget);
            System.out.println(getAID().getLocalName() + ": waiting for the next purchase order.");
            
            targetBookTitle = "";
          } else {
            failureReason = "Book was sold to another agent in the meantime.";
            System.out.println(getAID().getLocalName() + ": purchase has failed. " + targetBookTitle + " was sold in the meantime.");
          }
          step = 4;
        } else {
          block();
        }
        break;
      }        
    }

    public boolean done() {
      if (step == 4 && bestSeller == null) {
        System.out.println(getAID().getLocalName() + ": Could not purchase '" + targetBookTitle + "'. Reason: " + failureReason);
      }
      return (step == 4);
    }
  }
}