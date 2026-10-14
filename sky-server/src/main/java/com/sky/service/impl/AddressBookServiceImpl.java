package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.entity.AddressBook;
import com.sky.exception.AddressBookBusinessException;
import com.sky.mapper.AddressBookMapper;
import com.sky.service.AddressBookService;
import com.sky.service.support.UserWriteLock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AddressBookServiceImpl implements AddressBookService {
    private final AddressBookMapper addressBookMapper;
    private final UserWriteLock userWriteLock;

    public List<AddressBook> list(AddressBook filter) {
        filter.setUserId(BaseContext.getCurrentId());
        return addressBookMapper.list(filter);
    }
    @Transactional
    public void save(AddressBook address) {
        address.setUserId(userWriteLock.lock()); validate(address);
        address.setId(null); address.setIsDefault(0); addressBookMapper.insert(address);
    }
    public AddressBook getById(Long id) {
        AddressBook row = addressBookMapper.getById(id);
        if (row == null || !Objects.equals(row.getUserId(), BaseContext.getCurrentId())) {
            throw new AddressBookBusinessException("地址不存在或无权访问");
        }
        return row;
    }
    @Transactional
    public void update(AddressBook address) {
        userWriteLock.lock(); AddressBook old = getById(address.getId()); validate(address);
        address.setUserId(old.getUserId()); address.setIsDefault(null);
        // 保留历史性别值，不批量“纠正”旧端含义。
        address.setSex(old.getSex());
        addressBookMapper.update(address);
    }
    @Transactional
    public void setDefault(AddressBook input) {
        userWriteLock.lock(); AddressBook owned = getById(input.getId());
        AddressBook reset = AddressBook.builder().userId(owned.getUserId()).isDefault(0).build();
        addressBookMapper.updateIsDefaultByUserId(reset);
        addressBookMapper.update(AddressBook.builder().id(owned.getId()).userId(owned.getUserId()).isDefault(1).build());
    }
    @Transactional
    public void deleteById(Long id) {
        userWriteLock.lock(); getById(id); addressBookMapper.deleteById(id);
    }
    private void validate(AddressBook a) {
        if (!StringUtils.hasText(a.getConsignee()) || a.getConsignee().length() > 32 ||
            a.getPhone() == null || !a.getPhone().matches("1[0-9]{10}") ||
            !StringUtils.hasText(a.getProvinceName()) || !StringUtils.hasText(a.getCityName()) ||
            !StringUtils.hasText(a.getDistrictName()) || !StringUtils.hasText(a.getDetail()) ||
            a.getDetail().length() > 120 || (a.getLabel() != null && a.getLabel().length() > 16)) {
            throw new AddressBookBusinessException("请填写有效的联系人、手机号、地区及详细地址");
        }
    }
}
