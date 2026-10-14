package com.sky.service;
import com.sky.dto.OrdersConfirmDTO;
import com.sky.entity.*;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.*;
import com.sky.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class AdminOrderRegressionTest {
 @Test void merchantCannotReviveCancelledOrderByConfirming() {
  OrderMapper mapper=mock(OrderMapper.class);OrderServiceImpl service=new OrderServiceImpl(mapper,mock(OrderDetailMapper.class));
  OrdersConfirmDTO dto=new OrdersConfirmDTO();dto.setId(1L);
  assertThrows(OrderBusinessException.class,()->service.confirm(dto));verify(mapper).updateExpected(any(),eq(Orders.TO_BE_CONFIRMED));verify(mapper,never()).update(any());
 }
 @Test void adminSharedDetailNotRestrictedToCustomer() {
  OrderMapper mapper=mock(OrderMapper.class);OrderDetailMapper detail=mock(OrderDetailMapper.class);
  when(mapper.getById(1L)).thenReturn(Orders.builder().id(1L).userId(999L).build());when(detail.getByOrderId(1L)).thenReturn(Collections.emptyList());
  assertEquals(999L,new OrderServiceImpl(mapper,detail).details(1L).getUserId());
 }
}
