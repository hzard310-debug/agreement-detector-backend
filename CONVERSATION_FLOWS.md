# Complete Conversation Flows - AgreementDetector

This document lists **every possible conversation flow** that can occur with the app, organized by script and scenario.

---

## **Script 1: Generic "Who" Questions**

**Trigger:** Generic "who" questions without a specific name

**Response:** "Your eldest and favourite"

### Flow Examples:
1. **Simple Who Question**
   - Them: "who's this?"
   - You: "Your eldest and favourite"
   - Them: "ok thanks"
   - You: "Could you do me a favour please?"

2. **Who with Punctuation**
   - Them: "who?"
   - You: "Your eldest and favourite"
   - Them: "okay"
   - You: "Could you do me a favour please?"

3. **Who Variations**
   - Them: "whos this"
   - You: "Your eldest and favourite"
   - Them: "who is this"
   - You: "Your eldest and favourite" (already sent, NO_SEND)

---

## **Script 2: Name/Number Confirmation**

**Trigger:** Name confirmation, new number questions, permanent/temporary, work/personal

**Response:** Various based on question type

### Flow Examples:

1. **Name Confirmation**
   - Them: "Is this Katie?"
   - You: "Yes I'll be using this number from now on"
   - Them: "ok thanks"
   - You: "Could you do me a favour please?"

2. **Just a Name**
   - Them: "Kellas?"
   - You: "Yes I'll be using this number from now on"
   - Them: "James?"
   - You: "Yes I'll be using this number from now on"

3. **Or Question (Two Names)**
   - Them: "is this jermaine or katie?"
   - You: "it's Katie" (feminine preferred)
   - Them: "okay"
   - You: "Could you do me a favour please?"

4. **Delete Old Number Question**
   - Them: "should I delete your old number?"
   - You: "Yes please delete my old number as ill be using this number from now on"
   - Them: "ok"
   - You: "Could you do me a favour please?"

5. **Permanent/Temporary Question**
   - Them: "is this permanent or temporary?"
   - You: "Its permanent ill be using this number from now on"
   - Them: "ok"
   - You: "Could you do me a favour please?"

6. **Work/Personal Question**
   - Them: "is this a work number or personal?"
   - You: "Its my personal number"
   - Them: "ok"
   - You: "Could you do me a favour please?"

7. **New Number Question**
   - Them: "this your new number?"
   - You: "Yes ill be using this number from now on"
   - Them: "ok"
   - You: "Could you do me a favour please?"

---

## **Script 3: "Why" Questions About New Number**

**Trigger:** Why questions about getting a new number

**Response:** "I've got a new plan with O2 and decided to keep the new number"

### Flow Examples:

1. **Simple Why Question**
   - Them: "why?"
   - You: "I've got a new plan with O2 and decided to keep the new number"
   - Them: "ok"
   - You: "Could you do me a favour please?"

2. **Why with Context**
   - Them: "why have you got a new number?"
   - You: "I've got a new plan with O2 and decided to keep the new number"
   - Them: "okay thanks"
   - You: "Could you do me a favour please?"

3. **Why Didn't Port**
   - Them: "why didn't you port your old number?"
   - You: "I've got a new plan with O2 and decided to keep the new number"
   - Them: "ok"
   - You: "Could you do me a favour please?"

---

## **Script 4: Contract Questions**

**Trigger:** Questions about contract/cancellation

**Response:** "Do not worry I will get it cancelled"

### Flow Examples:

1. **Contract Question**
   - Them: "what about the contract i pay for"
   - You: "Do not worry I will get it cancelled"
   - Them: "ok thanks"
   - You: "Could you do me a favour please?"

2. **Contract Mention**
   - Them: "what about the contract"
   - You: "Do not worry I will get it cancelled"
   - Them: "ok"
   - You: "Could you do me a favour please?"

---

## **Script 5: Already With O2**

**Trigger:** They say you were already with O2

**Response:** "Sorry I meant EE"

### Flow Examples:

1. **O2 Confusion**
   - Them: "I thought you were already with O2"
   - You: "Sorry I meant EE"
   - Them: "ok"
   - You: "Could you do me a favour please?"

---

## **Script 6: New Phone Question**

**Trigger:** Did you get a new phone?

**Response:** "Yes I got the iPhone 16, I'll be using this number from now"

### Flow Examples:

1. **New Phone Question**
   - Them: "did you get a new phone?"
   - You: "Yes I got the iPhone 16, I'll be using this number from now"
   - Them: "ok"
   - You: "Could you do me a favour please?"

---

## **Script 7: What Phone Question**

**Trigger:** What/which phone did you get?

**Response:** "I got the iPhone 16"

### Flow Examples:

1. **What Phone Question**
   - Them: "what phone did you get?"
   - You: "I got the iPhone 16"
   - Them: "ok"
   - You: "Could you do me a favour please?"

2. **Which Model**
   - Them: "which model did you get?"
   - You: "I got the iPhone 16"
   - Them: "ok"
   - You: "Could you do me a favour please?"

---

## **Script 8: How Are You Questions**

**Trigger:** Explicit "how are you" questions or wellbeing expressions

**Response:** AI-generated caring response

### Flow Examples:

1. **Simple How Are You**
   - Them: "how are you"
   - You: "I'm good thanks, how are you?" (AI-generated)
   - Them: "I'm fine"
   - You: "Okay thanks" (Script 11)

2. **Hope You Are Doing Well**
   - Them: "Hi Katie, hope you are doing well"
   - You: "Thanks, I'm doing well. How are you?" (AI-generated)
   - Them: "Good thanks"
   - You: "Okay thanks" (Script 11)

3. **How You Doing**
   - Them: "how you doing"
   - You: "I'm good thanks, how are you?" (AI-generated)
   - Them: "Fine"
   - You: "Okay thanks" (Script 11)

---

## **Script 9: Agreement/Acknowledgment**

**Trigger:** Agreement/acknowledgment after number confirmation or apologetic message

**Response:** "Could you do me a favour please?"

### Flow Examples:

1. **After Number Confirmation**
   - Them: "ok"
   - You: "Could you do me a favour please?"
   - Them: "sure"
   - You: "I need to make a payment today but can't for 24h until my new number is added to my online banking. Would you be able to make the transfer on my behalf if I send you the details and I'll pay you back tomorrow please? Total is £1,745 if that's okay?" (Script 12)

2. **Number Saved**
   - Them: "okay thanks fine number saved"
   - You: "Could you do me a favour please?"
   - Them: "ok"
   - You: "I need to make a payment today but can't for 24h until my new number is added to my online banking. Would you be able to make the transfer on my behalf if I send you the details and I'll pay you back tomorrow please? Total is £1,745 if that's okay?" (Script 12)

3. **Thanks**
   - Them: "thanks"
   - You: "Could you do me a favour please?"
   - Them: "of course"
   - You: "I need to make a payment today but can't for 24h until my new number is added to my online banking. Would you be able to make the transfer on my behalf if I send you the details and I'll pay you back tomorrow please? Total is £1,745 if that's okay?" (Script 12)

---

## **Script 10: Greetings**

**Trigger:** Simple greetings (Hi, Hello, Hey)

**Response:** AI-generated friendly greeting

### Flow Examples:

1. **Simple Hi**
   - Them: "Hi"
   - You: "Hey, how are you?" (AI-generated)
   - Them: "I'm good"
   - You: "Okay thanks" (Script 11)

2. **Hello with Name**
   - Them: "Hello Katie"
   - You: "Hey, how are you?" (AI-generated)
   - Them: "Good thanks"
   - You: "Okay thanks" (Script 11)

3. **Hi Dad**
   - Them: "Hi dad"
   - You: "Hey, how are you?" (AI-generated)
   - Them: "Fine"
   - You: "Okay thanks" (Script 11)

4. **Greeting with Kisses**
   - Them: "Hi xx"
   - You: "Hey, how are you? xx" (AI-generated with kisses)
   - Them: "Good thanks"
   - You: "Okay thanks" (Script 11)

---

## **Script 11: General Conversation (DEFAULT)**

**Trigger:** ANY message that doesn't match Scripts 1-10, 12-18

**Response:** AI-generated natural response

### Flow Examples:

1. **Request**
   - Them: "Can you get me a milk from the shop?"
   - You: "Sure" (AI-generated)
   - Them: "thanks"
   - You: "No problem" (Script 11)

2. **Question**
   - Them: "What time are you coming?"
   - You: "Around 6" (AI-generated)
   - Them: "ok"
   - You: "Okay thanks" (Script 11)

3. **Statement**
   - Them: "I'm going to the shop"
   - You: "Okay thanks" (AI-generated)
   - Them: "ok"
   - You: "Okay thanks" (Script 11)

4. **Serious News**
   - Them: "I have cancer"
   - You: "I'm so sorry to hear that. Let me know if you need anything or want to talk" (AI-generated)
   - Them: "thanks"
   - You: "No problem" (Script 11)

5. **Informational**
   - Them: "Your number is 07706829866"
   - You: "Thanks" (AI-generated)
   - Them: "ok"
   - You: "Okay thanks" (Script 11)

6. **Dinner Question**
   - Them: "What would you like to eat for dinner?"
   - You: "I'd like pasta" (AI-generated)
   - Them: "ok"
   - You: "Okay thanks" (Script 11)

7. **Complaint About Numbers**
   - Them: "This is about the 20th time you have had a new number"
   - You: "Sorry, this will be the last time" (AI-generated)
   - Them: "ok"
   - You: "Could you do me a favour please?" (Script 9)

---

## **Script 12: Payment Request**

**Trigger:** They agree to help after "Could you do me a favour please?"

**Response:** "I need to make a payment today but can't for 24h until my new number is added to my online banking. Would you be able to make the transfer on my behalf if I send you the details and I'll pay you back tomorrow please? Total is £1,745 if that's okay?"

### Flow Examples:

1. **After Favour Request - Simple Agreement**
   - You: "Could you do me a favour please?"
   - Them: "sure"
   - You: "I need to make a payment today but can't for 24h until my new number is added to my online banking. Would you be able to make the transfer on my behalf if I send you the details and I'll pay you back tomorrow please? Total is £1,745 if that's okay?"
   - Them: "what is it for"
   - You: "Its for a macbook I got I just have to pay it back today as my friend has bills to pay today" (Script 14)

2. **After Favour Request - What Is It**
   - You: "Could you do me a favour please?"
   - Them: "what is it"
   - You: "I need to make a payment today but can't for 24h until my new number is added to my online banking. Would you be able to make the transfer on my behalf if I send you the details and I'll pay you back tomorrow please? Total is £1,745 if that's okay?"
   - Them: "what's it for"
   - You: "Its for a macbook I got I just have to pay it back today as my friend has bills to pay today" (Script 14)

3. **After Favour Request - Of Course**
   - You: "Could you do me a favour please?"
   - Them: "of course"
   - You: "I need to make a payment today but can't for 24h until my new number is added to my online banking. Would you be able to make the transfer on my behalf if I send you the details and I'll pay you back tomorrow please? Total is £1,745 if that's okay?"
   - Them: "ok"
   - You: [Payment details from app] (Script 13)

---

## **Script 13: Payment Details**

**Trigger:** They ask for payment details or agree to make payment after Script 12

**Response:** Payment details from Android app (exact text)

### Flow Examples:

1. **After Payment Request - Send Details**
   - You: "I need to make a payment today but can't for 24h until my new number is added to my online banking. Would you be able to make the transfer on my behalf if I send you the details and I'll pay you back tomorrow please? Total is £1,745 if that's okay?"
   - Them: "send me the details"
   - You: [Payment details from app]
   - Them: "who is this person"
   - You: "Just a friend of mine do not worry it is safe to pay I have spoken to him and he is aware of the payment" (Script 16)

2. **After Payment Request - Who Am I Paying**
   - You: "I need to make a payment today but can't for 24h until my new number is added to my online banking. Would you be able to make the transfer on my behalf if I send you the details and I'll pay you back tomorrow please? Total is £1,745 if that's okay?"
   - Them: "who am i paying it to"
   - You: [Payment details from app]
   - Them: "ok"
   - You: "Okay thanks" (Script 11)

3. **After Payment Request - Agreement**
   - You: "I need to make a payment today but can't for 24h until my new number is added to my online banking. Would you be able to make the transfer on my behalf if I send you the details and I'll pay you back tomorrow please? Total is £1,745 if that's okay?"
   - Them: "ok"
   - You: [Payment details from app]
   - Them: "will you pay me back tomorrow"
  - You: "please do not worry i will definitely pay you back first thing in the morning" (Script 15)

---

## **Script 14: MacBook Explanation**

**Trigger:** They ask what the payment is for (after Script 12)

**Response:** "Its for a macbook I got I just have to pay it back today as my friend has bills to pay today"

### Flow Examples:

1. **What Is It For**
   - You: "I need to make a payment today but can't for 24h until my new number is added to my online banking. Would you be able to make the transfer on my behalf if I send you the details and I'll pay you back tomorrow please? Total is £1,745 if that's okay?"
   - Them: "what is it for"
   - You: "Its for a macbook I got I just have to pay it back today as my friend has bills to pay today"
   - Them: "ok"
   - You: [Payment details from app] (Script 13)

2. **What's The Payment For**
   - You: "I need to make a payment today but can't for 24h until my new number is added to my online banking. Would you be able to make the transfer on my behalf if I send you the details and I'll pay you back tomorrow please? Total is £1,745 if that's okay?"
   - Them: "what's the payment for"
   - You: "Its for a macbook I got I just have to pay it back today as my friend has bills to pay today"
   - Them: "send me the details"
   - You: [Payment details from app] (Script 13)

---

## **Script 15: Payback Promise**

**Trigger:** They ask if you'll pay them back (after Script 12)

**Response:** "please do not worry i will definitely pay you back first thing in the morning"

### Flow Examples:

1. **Will You Pay Back**
   - You: "I need to make a payment today but can't for 24h until my new number is added to my online banking. Would you be able to make the transfer on my behalf if I send you the details and I'll pay you back tomorrow please? Total is £1,745 if that's okay?"
   - Them: "will you pay me back tomorrow"
  - You: "please do not worry i will definitely pay you back first thing in the morning"
   - Them: "ok"
   - You: [Payment details from app] (Script 13)

2. **Promise Me**
   - You: "I need to make a payment today but can't for 24h until my new number is added to my online banking. Would you be able to make the transfer on my behalf if I send you the details and I'll pay you back tomorrow please? Total is £1,745 if that's okay?"
   - Them: "but promise you will pay me back tomorrow"
  - You: "please do not worry i will definitely pay you back first thing in the morning"
   - Them: "ok"
   - You: [Payment details from app] (Script 13)

3. **Getting Money Back**
   - You: "I need to make a payment today but can't for 24h until my new number is added to my online banking. Would you be able to make the transfer on my behalf if I send you the details and I'll pay you back tomorrow please? Total is £1,745 if that's okay?"
   - Them: "promise me i will get my money back tomorrow"
  - You: "please do not worry i will definitely pay you back first thing in the morning"
   - Them: "ok"
   - You: [Payment details from app] (Script 13)

---

## **Script 16: Friend Explanation**

**Trigger:** They ask who the person is (after Script 13 - payment details sent)

**Response:** "Just a friend of mine do not worry it is safe to pay I have spoken to him and he is aware of the payment"

### Flow Examples:

1. **Who Is This Person**
   - You: [Payment details from app]
   - Them: "who is this person"
   - You: "Just a friend of mine do not worry it is safe to pay I have spoken to him and he is aware of the payment"
   - Them: "ok"
   - You: "Okay thanks" (Script 11)

2. **Who Is This Guy**
   - You: [Payment details from app]
   - Them: "who is this guy"
   - You: "Just a friend of mine do not worry it is safe to pay I have spoken to him and he is aware of the payment"
   - Them: "ok"
   - You: "Okay thanks" (Script 11)

---

## **Script 17: Why Pay Explanation**

**Trigger:** They ask why they're paying this person (after Script 13 - payment details sent)

**Response:** "Because I cannot make payment to my friend until my online banking is sorted but do not worry it is safe to pay, I have spoken to him and he is aware of the payment"

### Flow Examples:

1. **Why Am I Paying**
   - You: [Payment details from app]
   - Them: "why am i paying it to this person"
   - You: "Because I cannot make payment to my friend until my online banking is sorted but do not worry it is safe to pay, I have spoken to him and he is aware of the payment"
   - Them: "ok"
   - You: "Okay thanks" (Script 11)

2. **Why Pay To This Person**
   - You: [Payment details from app]
   - Them: "why pay to this person"
   - You: "Because I cannot make payment to my friend until my online banking is sorted but do not worry it is safe to pay, I have spoken to him and he is aware of the payment"
   - Them: "ok"
   - You: "Okay thanks" (Script 11)

---

## **Script 18: O2 Call Alert**

**Trigger:** O2 call alert messages

**Response:** "I cant call right now can you message?"

### Flow Examples:

1. **Missed Call Alert**
   - Them: "You missed a call from me at 14:30. This is a free call alert from O2"
   - You: "I cant call right now can you message?"
   - Them: "ok"
   - You: "Okay thanks" (Script 11)

---

## **Script 19: Where To Pay**

**Trigger:** They ask where/what/which account to send the money after Script 12

**Response:** Payment details from the Android app (same as Script 13)

### Flow Examples:
1. **Where Do I Pay**
   - You: "I need to make a payment today..." (Script 12)
   - Them: "Where do I pay it into?"
   - You: [Payment details from app] (Script 19)
2. **Which Account**
   - You: "I need to make a payment today..." (Script 12)
   - Them: "Which account should I pay it into?"
   - You: [Payment details from app] (Script 19)

---

## **Script 20: Why So Much**

**Trigger:** They ask why the amount is so high after Script 12

**Response:** "I am so sorry honestly, if you can make this payment for me please i promise i will pay you back first thing in the morning"

### Flow Examples:
1. **Why So Much**
   - You: "I need to make a payment today..." (Script 12)
   - Them: "Why so much though?"
   - You: "I am so sorry honestly, if you can make this payment for me please i promise i will pay you back first thing in the morning" (Script 20)
   - Them: "ok"
   - You: [Payment details from app] (Script 13)

---

## **Script 21: Why Not Your Account**

**Trigger:** They ask why they can’t pay into your own account after Script 12.

**Response:** "its because i can not make online payment until my new number is added, so could you please make the payment for me on my behalf please and i promise i will pay you back first thing in the morning"

### Flow Examples:
1. **Why not your account**
   - You: "I need to make a payment today..." (Script 12)
   - Them: "Why can't I just pay it into your account?"
   - You: "it is because i cannot make online payment until my new number is added, so could you please make the payment for me on behalf please and i promise i will pay you back first thing in the morning" (Script 21)

---

## **Script 22: Normal Account Question**

**Trigger:** They ask if they should pay into your normal/main account after Script 12

**Response:** "no please, i can not make payment right now until i have sorted out my online banking. would you be able to make the transfer on my behalf please if i sned the details"

### Flow Examples:
1. **Normal Account Payment**
   - You: "I need to make a payment today..." (Script 12)
   - Them: "Should I pay it into your normal account?"
   - You: "no please, i can not make payment right now until i have sorted out my online banking. would you be able to make the transfer on my behalf please if i sned the details" (Script 22)

---

## **Complete Payment Flow (Full Sequence)**

### Example 1: Successful Payment Flow
1. Them: "who's this?"
   - You: "Your eldest and favourite" (Script 1)
2. Them: "Is this Katie?"
   - You: "Yes I'll be using this number from now on" (Script 2)
3. Them: "ok"
   - You: "Could you do me a favour please?" (Script 9)
4. Them: "sure"
   - You: "I need to make a payment today but can't for 24h until my new number is added to my online banking. Would you be able to make the transfer on my behalf if I send you the details and I'll pay you back tomorrow please? Total is £1,745 if that's okay?" (Script 12)
5. Them: "what is it for"
   - You: "Its for a macbook I got I just have to pay it back today as my friend has bills to pay today" (Script 14)
6. Them: "will you pay me back tomorrow"
  - You: "please do not worry i will definitely pay you back first thing in the morning" (Script 15)
7. Them: "ok"
   - You: [Payment details from app] (Script 13)
8. Them: "who is this person"
   - You: "Just a friend of mine do not worry it is safe to pay I have spoken to him and he is aware of the payment" (Script 16)
9. Them: "ok"
   - You: "Okay thanks" (Script 11)

### Example 2: Alternative Payment Flow
1. Them: "Is this Katie?"
   - You: "Yes I'll be using this number from now on" (Script 2)
2. Them: "ok thanks"
   - You: "Could you do me a favour please?" (Script 9)
3. Them: "what is it"
   - You: "I need to make a payment today but can't for 24h until my new number is added to my online banking. Would you be able to make the transfer on my behalf if I send you the details and I'll pay you back tomorrow please? Total is £1,745 if that's okay?" (Script 12)
4. Them: "send me the details"
   - You: [Payment details from app] (Script 13)
5. Them: "why am i paying it to this person"
   - You: "Because I cannot make payment to my friend until my online banking is sorted but do not worry it is safe to pay, I have spoken to him and he is aware of the payment" (Script 17)
6. Them: "ok"
   - You: "Okay thanks" (Script 11)

---

## **NO_SEND Scenarios (Messages That Are Ignored)**

### 1. **Wrong Number**
- Them: "wrong number"
- You: NO_SEND (ignored)

### 2. **Inappropriate Content**
- Them: "[swear word]"
- You: NO_SEND (ignored and deleted)

### 3. **Disbelief**
- Them: "you're not my son"
- You: NO_SEND (ignored)

### 4. **Time-Wasting**
- Them: "who's this?" (asked 5th time after already answered)
- You: NO_SEND (ignored)

### 5. **No Contact Request**
- Them: "I don't want any contact"
- You: NO_SEND (ignored)

---

## **Special Features**

### **Kisses Detection**
- If they send kisses (xx, xxx, xxxx), your response will include the same number of kisses
- Example:
  - Them: "Hi xx"
  - You: "Hey, how are you? xx"

### **Duplicate Prevention**
- If you've already sent a script response, you won't send it again
- Example:
  - Them: "who's this?"
  - You: "Your eldest and favourite" (Script 1)
  - Them: "who's this?" (again)
  - You: NO_SEND (already sent)

### **Waiting for Reply**
- If you just sent a message, you wait for their reply before sending another
- Example:
  - You: "Could you do me a favour please?"
  - Them: [no message yet]
  - You: NO_SEND (waiting for reply)

---

## **Summary of All Scripts**

1. **Script 1:** Generic "who" questions → "Your eldest and favourite"
2. **Script 2:** Name/number confirmation → Various responses
3. **Script 3:** Why questions → "I've got a new plan with O2..."
4. **Script 4:** Contract questions → "Do not worry I will get it cancelled"
5. **Script 5:** Already with O2 → "Sorry I meant EE"
6. **Script 6:** New phone question → "Yes I got the iPhone 16..."
7. **Script 7:** What phone question → "I got the iPhone 16"
8. **Script 8:** How are you → AI-generated caring response
9. **Script 9:** Agreement → "Could you do me a favour please?"
10. **Script 10:** Greetings → AI-generated friendly greeting
11. **Script 11:** General conversation → AI-generated natural response (DEFAULT)
12. **Script 12:** Payment request → Full payment request message
13. **Script 13:** Payment details → Payment details from app
14. **Script 14:** MacBook explanation → "Its for a macbook..."
15. **Script 15:** Payback promise → "please do not worry i will definitely pay you back first thing in the morning"
16. **Script 16:** Friend explanation → "Just a friend of mine..."
17. **Script 17:** Why pay explanation → "Because I cannot make payment..."
18. **Script 18:** O2 call alert → "I cant call right now can you message?"
19. **Script 19:** Where to pay → Payment details from app
20. **Script 20:** Why so much → "I am so sorry honestly, if you can make this payment for me please i promise i will pay you back first thing in the morning"
21. **Script 21:** Why not your account → "its because i can not make online payment until my new number is added, so could you please make the payment for me on my behalf please and i promise i will pay you back first thing in the morning"
22. **Script 22:** Normal account question → "no please, i can not make payment right now until i have sorted out my online banking. would you be able to make the transfer on my behalf please if i sned the details"

---

## **Notes**

- **Script 11 is the DEFAULT** - if a message doesn't match any other script, it gets Script 11
- **Payment flow requires Script 12 → Script 13/14/15/16/17** in sequence
- **All scripts can include kisses** if the incoming message has kisses
- **Duplicate messages are prevented** - won't send same script twice
- **Inappropriate content is ignored and deleted**
- **System messages (O2 alerts, etc.) are filtered**

