package com.sky.service;
import com.sky.context.BaseContext;
import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.*;
import com.sky.exception.*;
import com.sky.mapper.*;
import com.sky.service.impl.*;
import com.sky.service.support.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AddressAndCartTest {
    @BeforeEach void setup() { BaseContext.setCurrentId(1L); }
    @AfterEach void clear() { BaseContext.removeCurrentId(); }
    @Test void foreignDefaultDoesNotResetOwnAddresses() {
        AddressBookMapper mapper=mock(AddressBookMapper.class);UserWriteLock lock=mock(UserWriteLock.class);
        AddressBookServiceImpl service=new AddressBookServiceImpl(mapper,lock);
        when(mapper.getById(8L)).thenReturn(AddressBook.builder().id(8L).userId(2L).build());
        assertThrows(AddressBookBusinessException.class,()->service.setDefault(AddressBook.builder().id(8L).build()));
        assertThrows(AddressBookBusinessException.class,()->service.deleteById(8L));
        verify(mapper,never()).updateIsDefaultByUserId(any());verify(mapper,never()).deleteById(any());
    }
    @Test void flavorIdentityAndCurrentPrice() {
        DishMapper dish=mock(DishMapper.class);SetmealMapper setmeal=mock(SetmealMapper.class);DishFlavorMapper flavors=mock(DishFlavorMapper.class);ShopStatusService shop=mock(ShopStatusService.class);
        CartRules rules=new CartRules(dish,setmeal,flavors,shop);
        assertThrows(ShoppingCartBusinessException.class,()->rules.identity(ShoppingCart.builder().dishId(1L).setmealId(2L).build()));
        assertFalse(rules.same(ShoppingCart.builder().dishId(1L).build(),ShoppingCart.builder().dishId(1L).dishFlavor("辣").build()));
        assertTrue(rules.same(ShoppingCart.builder().dishId(1L).build(),ShoppingCart.builder().dishId(1L).dishFlavor("").build()));
        when(dish.getById(1L)).thenReturn(Dish.builder().id(1L).status(1).name("新菜").price(new BigDecimal("25")).build());
        when(flavors.getByDishId(1L)).thenReturn(Collections.singletonList(DishFlavor.builder().name("辣度").value("[\"微辣\",\"特辣\"]").build()));
        ShoppingCart row=ShoppingCart.builder().dishId(1L).dishFlavor("微辣").amount(BigDecimal.ONE).build();rules.hydrate(row);assertEquals(new BigDecimal("25"),row.getAmount());
        row.setDishFlavor("");assertThrows(ShoppingCartBusinessException.class,()->rules.hydrate(row));
    }
    @Test void mergeCombinesLegacyDuplicatesAndUsesNewPrice() {
        ShoppingCartMapper mapper=mock(ShoppingCartMapper.class);UserWriteLock lock=mock(UserWriteLock.class);
        CartRules rules=new CartRules(mock(DishMapper.class),mock(SetmealMapper.class),mock(DishFlavorMapper.class),mock(ShopStatusService.class));
        ShoppingCartServiceImpl service=new ShoppingCartServiceImpl(mapper,lock,rules);
        when(mapper.list(any())).thenReturn(Arrays.asList(ShoppingCart.builder().id(10L).dishId(1L).number(2).build(),ShoppingCart.builder().id(11L).dishId(1L).number(3).dishFlavor("").build()));
        ShoppingCart row=ShoppingCart.builder().dishId(1L).userId(1L).dishFlavor("").amount(new BigDecimal("20")).build();
        service.merge(row,1);ArgumentCaptor<ShoppingCart> capture=ArgumentCaptor.forClass(ShoppingCart.class);verify(mapper).updateSnapshot(capture.capture());
        assertEquals(6,capture.getValue().getNumber());verify(mapper).deleteById(11L);verify(mapper,never()).insert(any());
    }
    @Test void subEmptyFlavorDoesNotRemoveFlavoredItem() {
        ShoppingCartMapper mapper=mock(ShoppingCartMapper.class);UserWriteLock lock=mock(UserWriteLock.class);when(lock.lock()).thenReturn(1L);
        CartRules rules=new CartRules(mock(DishMapper.class),mock(SetmealMapper.class),mock(DishFlavorMapper.class),mock(ShopStatusService.class));
        when(mapper.list(any())).thenReturn(Collections.singletonList(ShoppingCart.builder().id(2L).dishId(1L).dishFlavor("辣").number(1).build()));
        ShoppingCartDTO dto=new ShoppingCartDTO();dto.setDishId(1L);new ShoppingCartServiceImpl(mapper,lock,rules).subShoppingCart(dto);verify(mapper,never()).deleteById(any());
    }
}
