package com.sky.service;
import com.sky.context.BaseContext;
import com.sky.dto.*;
import com.sky.entity.*;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.*;
import com.sky.service.impl.ShoppingCartServiceImpl;
import com.sky.service.support.*;
import com.sky.websocket.WebSocketServer;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserOrderServiceTest {
    OrderMapper orders = mock(OrderMapper.class);
    OrderDetailMapper details = mock(OrderDetailMapper.class);
    AddressBookService addresses = mock(AddressBookService.class);
    ShoppingCartMapper carts = mock(ShoppingCartMapper.class);
    ShoppingCartServiceImpl cart = mock(ShoppingCartServiceImpl.class);
    UserWriteLock lock = mock(UserWriteLock.class);
    CartRules rules = mock(CartRules.class);
    OrderService shared = mock(OrderService.class);
    WebSocketServer sockets = mock(WebSocketServer.class);
    UserOrderService service = new UserOrderService(orders, details, addresses, carts, cart, lock, rules, shared, sockets);
    @BeforeEach void setup() { BaseContext.setCurrentId(7L); when(lock.lock()).thenReturn(7L); }
    @AfterEach void clear() { BaseContext.removeCurrentId(); }

    Orders order(int status, int paid) {
        return Orders.builder().id(1L).userId(7L).status(status).payStatus(paid).number("n").orderTime(LocalDateTime.now()).build();
    }
    OrdersPaymentDTO payment() { OrdersPaymentDTO dto = new OrdersPaymentDTO(); dto.setOrderNumber("n"); dto.setPayMethod(1); return dto; }

    @Test void rejectForeignOrderEveryUserEntry() {
        Orders foreign = order(2,1); foreign.setUserId(8L); when(orders.getById(1L)).thenReturn(foreign);
        assertThrows(OrderBusinessException.class, () -> service.detail(1L));
        assertThrows(OrderBusinessException.class, () -> service.cancel(1L));
        assertThrows(OrderBusinessException.class, () -> service.repeat(1L));
        assertThrows(OrderBusinessException.class, () -> service.remind(1L));
        verifyNoInteractions(shared, details, carts, cart, sockets);
    }
    @Test void serverRecalculatesTamperedAmountAndFullAddress() {
        OrdersSubmitDTO dto = new OrdersSubmitDTO(); dto.setAddressBookId(2L); dto.setPayMethod(1); dto.setDeliveryStatus(1); dto.setTablewareStatus(1);
        dto.setPackAmount(-999); dto.setAmount(new BigDecimal("0.01"));
        when(addresses.getById(2L)).thenReturn(AddressBook.builder().provinceName("省").cityName("市").districtName("区").detail("街1号").build());
        when(carts.list(any())).thenReturn(Arrays.asList(ShoppingCart.builder().dishId(9L).number(2).amount(new BigDecimal("12.30")).build()));
        doAnswer(inv -> { ((Orders)inv.getArgument(0)).setId(42L); return null; }).when(orders).insert(any());
        assertEquals(new BigDecimal("32.60"), service.submit(dto).getOrderAmount());
        ArgumentCaptor<Orders> captured = ArgumentCaptor.forClass(Orders.class); verify(orders).insert(captured.capture());
        assertEquals(2, captured.getValue().getPackAmount()); assertEquals(2, captured.getValue().getTablewareNumber());
        assertEquals("省市区街1号", captured.getValue().getAddress()); verify(carts).deleteByUserId(7L);
    }
    @Test void emptyCartPreventsDuplicateSubmit() {
        OrdersSubmitDTO dto = new OrdersSubmitDTO();dto.setAddressBookId(2L);dto.setPayMethod(1);dto.setDeliveryStatus(1);dto.setTablewareStatus(0);
        when(addresses.getById(2L)).thenReturn(new AddressBook()); when(carts.list(any())).thenReturn(Collections.emptyList());
        assertThrows(OrderBusinessException.class,()->service.submit(dto));verify(orders,never()).insert(any());
    }
    @Test void successfulPaymentIsConditionalAndNotifiesOnce() {
        when(orders.getByNumberAndUserId("n",7L)).thenReturn(order(1,0)); when(orders.payIfPending(eq(1L),eq(7L),any())).thenReturn(1);
        service.payment(payment()); verify(sockets,times(1)).sendToAllClient(anyString());
        when(orders.getByNumberAndUserId("n",7L)).thenReturn(order(2,1));
        service.payment(payment()); verify(sockets,times(1)).sendToAllClient(anyString()); verify(orders,times(1)).payIfPending(anyLong(),anyLong(),any());
    }
    @Test void expiredAndCancelledCannotPay() {
        Orders old=order(1,0);old.setOrderTime(LocalDateTime.now().minusMinutes(16)); when(orders.getByNumberAndUserId("n",7L)).thenReturn(old);
        assertThrows(OrderBusinessException.class,()->service.payment(payment()));
        when(orders.getByNumberAndUserId("n",7L)).thenReturn(order(6,0));assertThrows(OrderBusinessException.class,()->service.payment(payment()));verifyNoInteractions(sockets);
    }
    @Test void lostPaymentRaceNeverNotifies() {
        when(orders.getByNumberAndUserId("n",7L)).thenReturn(order(1,0)); when(orders.payIfPending(anyLong(),anyLong(),any())).thenReturn(0);
        assertThrows(OrderBusinessException.class,()->service.payment(payment())); verifyNoInteractions(sockets);
    }
    @Test void cancellationUsesExpectedStatusAndSimulatedRefund() {
        when(orders.getById(1L)).thenReturn(order(2,1)); when(orders.updateExpected(any(),eq(2))).thenReturn(1);
        service.cancel(1L);ArgumentCaptor<Orders> update=ArgumentCaptor.forClass(Orders.class);verify(orders).updateExpected(update.capture(),eq(2));
        assertEquals(Orders.REFUND,update.getValue().getPayStatus());
    }
    @Test void lostCancelRaceFailsRatherThanOverwritingMerchant() {
        when(orders.getById(1L)).thenReturn(order(2,1));assertThrows(OrderBusinessException.class,()->service.cancel(1L));
    }
    @Test void reminderOnlyForActivePaidOrders() {
        when(orders.getById(1L)).thenReturn(order(1,0));assertThrows(OrderBusinessException.class,()->service.remind(1L));
        when(orders.getById(1L)).thenReturn(order(3,1));service.remind(1L);verify(sockets).sendToAllClient(contains("\"type\":2"));
    }
    @Test void repeatValidatesAllBeforeMutating() {
        when(orders.getById(1L)).thenReturn(order(5,1));
        when(details.getByOrderId(1L)).thenReturn(Arrays.asList(OrderDetail.builder().dishId(1L).number(1).build(),OrderDetail.builder().dishId(2L).number(1).build()));
        doAnswer(inv -> { ShoppingCart row=inv.getArgument(0);if(row.getDishId()==2L)throw new OrderBusinessException("下架");return null; }).when(rules).hydrate(any());
        assertThrows(OrderBusinessException.class,()->service.repeat(1L));verifyNoInteractions(cart);
    }
    @Test void pastScheduledDeliveryRejected() {
        OrdersSubmitDTO dto=new OrdersSubmitDTO();dto.setAddressBookId(2L);dto.setPayMethod(1);dto.setDeliveryStatus(0);dto.setTablewareStatus(0);dto.setEstimatedDeliveryTime(LocalDateTime.now().minusDays(1));
        assertThrows(OrderBusinessException.class,()->service.submit(dto));verifyNoInteractions(carts);
    }
}
